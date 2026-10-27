package com.dataroute.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TUN_IP = Ip.parse("198.18.77.2")!!
private val NVR_IP = Ip.parse("192.168.1.10")!!        // "local" -> Wi-Fi
private val WEB_IP = Ip.parse("142.250.80.46")!!       // "internet" -> cellular
private val DEAD_IP = Ip.parse("192.168.1.99")!!       // upstream refuses

/** Plays the part of the TUN device: engine output lands in [outbound], engine input is [ForwardEngine.onPacket]. */
private class Harness(val serverPort: Int, val udpPort: Int = 0, val cellularUp: Boolean = true) {
    val outbound = LinkedBlockingQueue<ByteArray>()
    val used = ConcurrentLinkedQueue<Pair<Path, Int>>()
    val events = ConcurrentLinkedQueue<String>()
    val upstream = object : Upstream {
        override fun resolve(wanted: Path) = if (wanted == Path.CELLULAR && !cellularUp) Path.WIFI else wanted
        override fun openTcp(path: Path, dstIp: Int, dstPort: Int): Socket {
            used += path to dstIp
            if (dstIp == DEAD_IP) throw IOException("refused")
            return Socket().also { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort), 2000) }
        }
        override fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket {
            used += path to dstIp
            return DatagramSocket().also { it.connect(InetAddress.getLoopbackAddress(), udpPort) }
        }
    }
    /** Number of upcoming server->app data segments to silently lose (simulates TUN overflow). */
    val dropData = java.util.concurrent.atomic.AtomicInteger(0)
    val engine = ForwardEngine(
        {
            val ip = Ipv4(it, it.size)
            val lose = ip.protocol == PROTO_TCP && TcpSegment(ip).payloadLen > 0 && dropData.get() > 0 && dropData.getAndDecrement() > 0
            if (!lose) {
                // deliver to the fake client that owns the destination port, else to the raw queue
                val owner = if (ip.protocol == PROTO_TCP) clients[TcpSegment(ip).dstPort] else null
                if (owner != null) owner.inbox.add(it) else outbound.add(it)
            }
        }, upstream,
        RoutePolicy(listOf(Cidr.parse("192.168.1.0/24")!!)),
        { events += it },
    )
    private var nextPort = 40000
    val clients = java.util.concurrent.ConcurrentHashMap<Int, FakeTcpClient>()
    fun client(dstIp: Int, dstPort: Int = 443) = FakeTcpClient(this, dstIp, dstPort, nextPort++).also { clients[it.port] = it }
    fun feed(pkt: ByteArray) = engine.onPacket(pkt, pkt.size)
}

/** Just enough of a TCP client to exercise the engine: in-order receive, windowed send, ACKs. */
private class FakeTcpClient(val h: Harness, val dstIp: Int, val dstPort: Int, val port: Int) {
    private val isn = 1_000_000 + port
    @Volatile var sndNxt = isn
    @Volatile var sndUna = isn
    @Volatile var serverWindow = 65535
    @Volatile var rcvNxt = 0
    @Volatile var gotSynAck = false
    @Volatile var gotFin = false
    @Volatile var gotRst = false
    val received = ByteArrayOutputStream()
    val inbox = LinkedBlockingQueue<ByteArray>()
    private val mon = Object()
    @Volatile private var running = true
    private val rx = thread(isDaemon = true) {
        while (running) {
            val p = inbox.poll(50, TimeUnit.MILLISECONDS) ?: continue
            val ip = Ipv4(p, p.size)
            if (!ip.valid || ip.protocol != PROTO_TCP) continue
            val t = TcpSegment(ip)
            if (t.dstPort != port || ip.src != dstIp || t.srcPort != dstPort) continue
            synchronized(mon) {
                if (t.has(TCP_RST)) gotRst = true
                if (t.has(TCP_SYN) && t.has(TCP_ACK)) { rcvNxt = t.seq + 1; gotSynAck = true }
                if (t.has(TCP_ACK)) { sndUna = t.ack; serverWindow = t.window }
                val len = t.payloadLen
                if (len > 0) {
                    if (t.seq == rcvNxt) {
                        received.write(p, t.payloadOffset, len); rcvNxt += len
                    }
                    ack()
                }
                if (t.has(TCP_FIN) && t.seq + len == rcvNxt) { rcvNxt++; gotFin = true; ack() }
                (mon as Object).notifyAll()
            }
        }
    }

    private fun raw(flags: Int, data: ByteArray? = null, off: Int = 0, len: Int = 0) =
        PacketBuilder.tcp(TUN_IP, port, dstIp, dstPort, sndNxt, rcvNxt, flags, 65535, data, off, len, if (flags and TCP_SYN != 0) 1460 else 0)

    private fun ack() = h.feed(raw(TCP_ACK))

    fun connect(timeoutMs: Long = 5000): Boolean {
        h.feed(raw(TCP_SYN)); sndNxt++
        return waitFor(timeoutMs) { gotSynAck || gotRst }.also { if (gotSynAck) ack() }  && gotSynAck
    }

    /** Sends [data] without ever exceeding the window the engine advertised (like a real TCP sender). */
    fun send(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            var n = 0
            synchronized(mon) {
                while (running && !gotRst) {
                    val allowed = serverWindow - (sndNxt - sndUna)
                    if (allowed > 0) { n = minOf(1400, data.size - off, allowed); break }
                    (mon as Object).wait(100)
                }
            }
            if (gotRst) error("reset while sending")
            h.feed(raw(TCP_ACK or TCP_PSH, data, off, n))
            sndNxt += n; off += n
        }
    }

    fun fin() { h.feed(raw(TCP_FIN or TCP_ACK)); sndNxt++ }
    fun waitAcked(timeoutMs: Long = 20000) = waitFor(timeoutMs) { sndUna == sndNxt }

    fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(mon) {
            while (!cond()) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                (mon as Object).wait(minOf(left, 100))
            }
        }
        return true
    }

    fun bytes(): ByteArray = synchronized(mon) { received.toByteArray() }
    fun stop() { running = false }
}

class EngineTest {
    private val closers = ArrayList<() -> Unit>()
    @AfterTest fun cleanup() { closers.forEach { runCatching { it() } } }

    private fun <T : AutoCloseable> T.autoClose(): T { closers += { close() }; return this }

    private fun pattern(n: Int, salt: Int = 0) = ByteArray(n) { ((it * 31 + salt) xor (it ushr 8)).toByte() }

    /** Echo server: replies with every byte received; closes when the client half-closes. */
    private fun echoServer(): ServerSocket {
        val ss = ServerSocket(0).autoClose()
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { return@thread }
                thread(isDaemon = true) {
                    s.use {
                        val buf = ByteArray(8192)
                        while (true) { val n = it.getInputStream().read(buf); if (n < 0) break; it.getOutputStream().write(buf, 0, n) }
                    }
                }
            }
        }
        return ss
    }

    @Test fun tcpEchoAndGracefulClose() {
        val ss = echoServer()
        val h = Harness(ss.localPort).also { closers += { it.engine.stop() } }
        val c = h.client(NVR_IP)
        assertTrue(c.connect(), "handshake")
        c.send("hello camera".toByteArray())
        assertTrue(c.waitFor(5000) { c.bytes().size == 12 })
        assertEquals("hello camera", String(c.bytes()))
        c.fin()
        assertTrue(c.waitFor(5000) { c.gotFin }, "server FIN after client FIN")
        assertTrue(c.waitAcked())
        assertTrue(c.waitFor(5000) { h.engine.stats.activeTcp.get() == 0 }, "flow cleaned up")
        c.stop()
        assertEquals(listOf(Path.WIFI), h.used.map { it.first }) // 192.168.1.10 is local -> Wi-Fi
        assertEquals(12L, h.engine.stats.wifiUp.get()); assertEquals(12L, h.engine.stats.wifiDown.get())
        assertEquals(0L, h.engine.stats.cellUp.get())
    }

    @Test fun routesInternetViaCellularAndLanViaWifi() {
        val ss = echoServer()
        val h = Harness(ss.localPort).also { closers += { it.engine.stop() } }
        val web = h.client(WEB_IP); val nvr = h.client(NVR_IP, 7441)
        assertTrue(web.connect()); assertTrue(nvr.connect())
        web.send(ByteArray(100)); nvr.send(ByteArray(50))
        assertTrue(web.waitFor(5000) { web.bytes().size == 100 }); assertTrue(nvr.waitFor(5000) { nvr.bytes().size == 50 })
        assertEquals(setOf(Path.CELLULAR to WEB_IP, Path.WIFI to NVR_IP), h.used.toSet())
        assertEquals(100L, h.engine.stats.cellUp.get()); assertEquals(50L, h.engine.stats.wifiUp.get())
        web.stop(); nvr.stop()
    }

    @Test fun fallbackPathIsWhatGetsAccounted() {
        val ss = echoServer()
        val h = Harness(ss.localPort, cellularUp = false).also { closers += { it.engine.stop() } }
        val c = h.client(WEB_IP) // internet destination, but there is no cellular network
        assertTrue(c.connect())
        c.send(ByteArray(10))
        assertTrue(c.waitFor(5000) { c.bytes().size == 10 })
        assertEquals(listOf(Path.WIFI to WEB_IP), h.used.toList())
        assertEquals(10L, h.engine.stats.wifiUp.get()); assertEquals(0L, h.engine.stats.cellUp.get())
        c.stop()
    }

    @Test fun bulkUploadAndDownloadKeepIntegrity() {
        val up = pattern(3_000_000, 7); val down = pattern(5_000_000, 99)
        val ss = ServerSocket(0).autoClose()
        val gotUp = ByteArrayOutputStream(); val upDone = CountDownLatch(1)
        thread(isDaemon = true) {
            ss.accept().use { s ->
                // read everything the client uploads while concurrently pushing the download
                val w = thread { s.getOutputStream().write(down); s.getOutputStream().flush() }
                val buf = ByteArray(65536)
                while (gotUp.size() < up.size) { val n = s.getInputStream().read(buf); if (n < 0) break; gotUp.write(buf, 0, n) }
                upDone.countDown(); w.join()
                s.shutdownOutput()
                while (s.getInputStream().read(buf) >= 0) { }
            }
        }
        val h = Harness(ss.localPort).also { closers += { it.engine.stop() } }
        val c = h.client(WEB_IP)
        assertTrue(c.connect())
        val sender = thread { c.send(up) }
        assertTrue(c.waitFor(60_000) { c.bytes().size >= down.size }, "download complete, got ${c.bytes().size}")
        sender.join(60_000)
        assertTrue(upDone.await(60, TimeUnit.SECONDS), "upload complete")
        assertContentEquals(up, gotUp.toByteArray())
        assertContentEquals(down, c.bytes())
        assertTrue(c.waitFor(10_000) { c.gotFin })
        c.fin(); assertTrue(c.waitFor(10_000) { h.engine.stats.activeTcp.get() == 0 })
        c.stop()
    }

    @Test fun lostSegmentsAreRetransmitted() {
        val data = pattern(200_000, 3)
        val ss = ServerSocket(0).autoClose()
        thread(isDaemon = true) { ss.accept().use { it.getOutputStream().write(data); it.getOutputStream().flush(); Thread.sleep(8000) } }
        val h = Harness(ss.localPort).also { closers += { it.engine.stop() } }
        h.dropData.set(5) // lose the first five segments of the burst
        val c = h.client(WEB_IP)
        assertTrue(c.connect())
        assertTrue(c.waitFor(30_000) { c.bytes().size == data.size }, "got ${c.bytes().size}")
        assertContentEquals(data, c.bytes())
        c.stop()
    }

    @Test fun serverClosesFirst() {
        val ss = ServerSocket(0).autoClose()
        thread(isDaemon = true) { ss.accept().use { it.getOutputStream().write("bye".toByteArray()) } }
        val h = Harness(ss.localPort).also { closers += { it.engine.stop() } }
        val c = h.client(WEB_IP)
        assertTrue(c.connect())
        assertTrue(c.waitFor(5000) { c.gotFin })
        assertEquals("bye", String(c.bytes()))
        c.fin()
        assertTrue(c.waitFor(5000) { h.engine.stats.activeTcp.get() == 0 })
        c.stop()
    }

    @Test fun upstreamFailureResetsTheApp() {
        val h = Harness(1).also { closers += { it.engine.stop() } }
        val c = h.client(DEAD_IP)
        assertFalse(c.connect(3000))
        assertTrue(c.gotRst)
        assertEquals(1L, h.engine.stats.tcpFailed.get())
        assertTrue(h.events.any { it.contains("failed") })
        assertTrue(c.waitFor(2000) { h.engine.stats.activeTcp.get() == 0 })
        c.stop()
    }

    @Test fun strayPacketGetsReset() {
        val h = Harness(1).also { closers += { it.engine.stop() } }
        h.feed(PacketBuilder.tcp(TUN_IP, 50000, NVR_IP, 443, 5, 9, TCP_ACK, 1000))
        val r = h.outbound.poll(2, TimeUnit.SECONDS)!!
        val t = TcpSegment(Ipv4(r, r.size))
        assertTrue(t.has(TCP_RST)); assertEquals(9, t.seq); assertEquals(50000, t.dstPort)
        // a RST must not be answered with another RST
        h.feed(PacketBuilder.tcp(TUN_IP, 50000, NVR_IP, 443, 5, 9, TCP_RST, 0))
        assertEquals(null, h.outbound.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun duplicateSynResendsSynAck() {
        val ss = echoServer()
        val h = Harness(ss.localPort).also { closers += { it.engine.stop() } }
        val syn = PacketBuilder.tcp(TUN_IP, 41000, NVR_IP, 443, 777, 0, TCP_SYN, 65535, mss = 1460)
        h.feed(syn)
        val first = h.outbound.poll(5, TimeUnit.SECONDS)!!
        h.feed(syn) // app never saw/acked the SYN-ACK and retransmitted
        val second = h.outbound.poll(5, TimeUnit.SECONDS)!!
        val a = TcpSegment(Ipv4(first, first.size)); val b = TcpSegment(Ipv4(second, second.size))
        assertTrue(a.has(TCP_SYN) && b.has(TCP_SYN)); assertEquals(a.seq, b.seq); assertEquals(778, a.ack)
        assertEquals(1, h.used.size, "only one upstream connection")
    }

    @Test fun clientResetClosesUpstream() {
        val closedUpstream = CountDownLatch(1)
        val ss = ServerSocket(0).autoClose()
        thread(isDaemon = true) { ss.accept().use { s -> while (s.getInputStream().read() >= 0) { }; closedUpstream.countDown() } }
        val h = Harness(ss.localPort).also { closers += { it.engine.stop() } }
        val c = h.client(WEB_IP)
        assertTrue(c.connect())
        h.feed(PacketBuilder.tcp(TUN_IP, c.port, WEB_IP, 443, c.sndNxt, 0, TCP_RST, 0))
        assertTrue(closedUpstream.await(5, TimeUnit.SECONDS))
        assertTrue(c.waitFor(2000) { h.engine.stats.activeTcp.get() == 0 })
        c.stop()
    }

    @Test fun udpRelay() {
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress()).autoClose()
        thread(isDaemon = true) {
            val buf = ByteArray(2000); val p = DatagramPacket(buf, buf.size)
            while (!echo.isClosed) {
                try { echo.receive(p) } catch (e: IOException) { return@thread }
                echo.send(DatagramPacket(buf, p.length, p.socketAddress))
            }
        }
        val h = Harness(1, echo.localPort).also { closers += { it.engine.stop() } }
        val q = "query".toByteArray()
        h.feed(PacketBuilder.udp(TUN_IP, 5000, Ip.parse("10.177.0.34")!!, 53, q, 0, q.size))
        val r = h.outbound.poll(5, TimeUnit.SECONDS)!!
        assertTrue(PacketBuilder.verifyL4(r))
        val ip = Ipv4(r, r.size); val d = UdpDatagram(ip)
        assertEquals(Ip.parse("10.177.0.34")!!, ip.src); assertEquals(TUN_IP, ip.dst)
        assertEquals(53, d.srcPort); assertEquals(5000, d.dstPort)
        assertEquals("query", String(r, d.payloadOffset, d.payloadLen))
        assertEquals(1, h.engine.stats.activeUdp.get())
        // second datagram reuses the flow
        h.feed(PacketBuilder.udp(TUN_IP, 5000, Ip.parse("10.177.0.34")!!, 53, q, 0, q.size))
        assertTrue(h.outbound.poll(5, TimeUnit.SECONDS) != null)
        assertEquals(1, h.used.size)
    }
}
