package com.dataroute.core

import java.io.IOException
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One app TCP connection. The app side is a minimal server-side TCP implementation (no window scaling, no SACK,
 * simple go-back-N retransmission, which is plenty for a loopback-like TUN link); the other side is a real
 * [Socket] bound to the right physical network.
 */
internal class TcpFlow(
    private val engine: ForwardEngine,
    private val key: FlowKey,
    clientIsn: Int,
    clientMss: Int,
    initialWindow: Int,
) {
    private class Seg(val seq: Int, val data: ByteArray, val flags: Int, var sentAt: Long) {
        val end: Int get() = seq + data.size + (if ((flags and TCP_FIN) != 0) 1 else 0)
    }

    private companion object {
        const val MAX_PENDING = 256 * 1024     // bytes accepted from the app but not yet written upstream
        const val ADV_WINDOW = 65535           // we never negotiate window scaling
        const val RTO_MS = 1000L
        const val IDLE_MS = 30L * 60_000
        const val HALF_CLOSED_IDLE_MS = 2L * 60_000
        val FIN_MARK = ByteArray(0)
        val POISON = ByteArray(0)
    }

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private val mss = if (clientMss > 0) minOf(clientMss, ForwardEngine.MSS) else 536
    private val iss: Int = ThreadLocalRandom.current().nextInt()
    private var sndUna = iss
    private var sndNxt = iss
    private var rcvNxt = clientIsn + 1
    private var peerWindow = initialWindow

    @Volatile private var closed = false
    private var established = false
    private var clientFin = false
    private var serverFinSent = false
    private var serverFinAcked = false
    private var lastAdvertised = ADV_WINDOW
    private var lastActivity = nowMs()

    private var socket: Socket? = null
    private var path = Path.CELLULAR
    private val outQueue = LinkedBlockingQueue<ByteArray>()
    private val pending = AtomicInteger()
    private val unacked = ArrayDeque<Seg>()

    private fun nowMs() = System.nanoTime() / 1_000_000

    // ---- connection setup / upstream side (runs on a pool thread) ----

    fun run() {
        path = engine.upstream.resolve(engine.router.pathFor(key.dstIp))
        val sock = try {
            engine.upstream.openTcp(path, key.dstIp, key.dstPort)
        } catch (e: Exception) {
            engine.stats.tcpFailed.incrementAndGet()
            engine.events("TCP $key via $path failed: ${e.message ?: e.javaClass.simpleName}")
            lock.withLock {
                if (!closed) {
                    emit(PacketBuilder.tcp(key.dstIp, key.dstPort, key.clientIp, key.clientPort, 0, rcvNxt, TCP_RST or TCP_ACK, 0))
                    closeLocked()
                }
            }
            return
        }
        lock.withLock {
            if (closed) { runCatching { sock.close() }; return }
            socket = sock
            established = true
            sendSynAckLocked()
        }
        engine.stats.tcpOpened.incrementAndGet()
        engine.events("TCP $key via $path connected")
        engine.executor.execute(::writerLoop)
        readerLoop(sock)
    }

    private fun readerLoop(sock: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            val input = sock.getInputStream()
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                engine.stats.down(path, n)
                if (!sendToClient(buf, n)) return
            }
        } catch (e: IOException) {
            if (!closed) abort(true)
            return
        }
        lock.withLock {
            if (!closed && !serverFinSent) {
                val fin = Seg(sndNxt, FIN_MARK, TCP_FIN or TCP_ACK, nowMs())
                unacked.addLast(fin)
                sndNxt++
                serverFinSent = true
                emitSeg(fin)
                checkDoneLocked()
            }
        }
    }

    /** Segments [n] bytes towards the app, waiting for the app's receive window. Returns false if closed. */
    private fun sendToClient(buf: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val sent = lock.withLock {
                var avail: Int
                while (true) {
                    if (closed) return false
                    avail = peerWindow - (sndNxt - sndUna)
                    if (avail > 0) break
                    cond.await(200, TimeUnit.MILLISECONDS)
                }
                val chunk = minOf(n - off, mss, avail)
                val seg = Seg(sndNxt, buf.copyOfRange(off, off + chunk), TCP_ACK or TCP_PSH, nowMs())
                unacked.addLast(seg)
                sndNxt += chunk
                emitSeg(seg)
                chunk
            }
            off += sent
        }
        return true
    }

    private fun writerLoop() {
        val sock = socket ?: return
        try {
            val out = sock.getOutputStream()
            while (true) {
                val item = outQueue.take()
                if (item === POISON) return
                if (item === FIN_MARK) {
                    runCatching { sock.shutdownOutput() }
                    return
                }
                out.write(item)
                pending.addAndGet(-item.size)
                maybeWindowUpdate()
            }
        } catch (e: IOException) {
            if (!closed) abort(true)
        } catch (_: InterruptedException) {
        }
    }

    // ---- app side (runs on the TUN reader thread) ----

    fun onDuplicateSyn() {
        lock.withLock {
            if (!closed && established && sndUna == iss) sendSynAckLocked()
        }
    }

    fun onSegment(seg: TcpSegment) {
        lock.withLock {
            if (closed) return
            lastActivity = nowMs()
            if (seg.has(TCP_RST)) { abortLocked(false); return }
            if (!established) return // still connecting upstream; the app will retransmit

            if (seg.has(TCP_ACK)) processAckLocked(seg.ack, seg.window)

            val len = seg.payloadLen
            if (len > 0) {
                val seq = seg.seq
                val skip = if (seqLt(seq, rcvNxt)) rcvNxt - seq else 0
                if (skip < len && seq + skip == rcvNxt) {
                    val take = len - skip
                    if (pending.get() + take > MAX_PENDING) {
                        emitAckLocked() // too much queued: drop, the app will retransmit
                        return
                    }
                    val start = seg.payloadOffset + skip
                    outQueue.add(seg.ip.buf.copyOfRange(start, start + take))
                    pending.addAndGet(take)
                    rcvNxt += take
                    engine.stats.up(path, take)
                }
                emitAckLocked() // new data, duplicate, or out of order: always answer with our state
            }

            if (seg.has(TCP_FIN)) {
                val finSeq = seg.seq + len
                if (finSeq == rcvNxt && !clientFin) {
                    clientFin = true
                    rcvNxt++
                    outQueue.add(FIN_MARK)
                    emitAckLocked()
                } else if (clientFin && finSeq == rcvNxt - 1) {
                    emitAckLocked() // our ACK of their FIN was lost
                }
            }
            checkDoneLocked()
        }
    }

    private fun processAckLocked(ack: Int, window: Int) {
        if (seqLe(sndUna, ack) && seqLe(ack, sndNxt)) {
            sndUna = ack
            peerWindow = window
            while (true) {
                val first = unacked.peekFirst() ?: break
                if (seqLe(first.end, ack)) unacked.removeFirst() else break
            }
            if (serverFinSent && ack == sndNxt) serverFinAcked = true
            cond.signalAll()
        }
    }

    private fun checkDoneLocked() {
        if (clientFin && serverFinSent && serverFinAcked) closeLocked()
    }

    private fun maybeWindowUpdate() {
        lock.withLock {
            if (!closed && lastAdvertised < 8192 && currentWindow() >= 32768) emitAckLocked()
        }
    }

    // ---- timers ----

    fun tick(now: Long) {
        lock.withLock {
            if (closed) return
            val first = unacked.peekFirst()
            if (first != null && now - first.sentAt > RTO_MS) {
                // go-back-N: the window is <= 64 KiB so this is at most ~47 packets
                for (seg in unacked) { seg.sentAt = now; emitSeg(seg) }
            }
            val limit = if (clientFin || serverFinSent) HALF_CLOSED_IDLE_MS else IDLE_MS
            if (now - lastActivity > limit) abortLocked(true)
        }
    }

    fun shutdown() = lock.withLock { closeLocked() }

    // ---- emit helpers (lock held) ----

    private fun currentWindow(): Int = (ADV_WINDOW - pending.get()).coerceIn(0, ADV_WINDOW)

    private fun emit(pkt: ByteArray) = engine.sink.write(pkt)

    private fun emitSeg(s: Seg) {
        lastActivity = nowMs()
        emit(PacketBuilder.tcp(key.dstIp, key.dstPort, key.clientIp, key.clientPort, s.seq, rcvNxt, s.flags, currentWindow(), s.data, 0, s.data.size))
    }

    private fun emitAckLocked() {
        lastAdvertised = currentWindow()
        emit(PacketBuilder.tcp(key.dstIp, key.dstPort, key.clientIp, key.clientPort, sndNxt, rcvNxt, TCP_ACK, lastAdvertised))
    }

    private fun sendSynAckLocked() {
        sndNxt = iss + 1
        emit(PacketBuilder.tcp(key.dstIp, key.dstPort, key.clientIp, key.clientPort, iss, rcvNxt, TCP_SYN or TCP_ACK, currentWindow(), mss = ForwardEngine.MSS))
    }

    private fun abort(sendRst: Boolean) = lock.withLock { abortLocked(sendRst) }

    private fun abortLocked(sendRst: Boolean) {
        if (closed) return
        if (sendRst) emit(PacketBuilder.tcp(key.dstIp, key.dstPort, key.clientIp, key.clientPort, sndNxt, rcvNxt, TCP_RST or TCP_ACK, 0))
        closeLocked()
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        outQueue.clear()
        outQueue.add(POISON)
        cond.signalAll()
        engine.removeTcp(key, this)
    }
}
