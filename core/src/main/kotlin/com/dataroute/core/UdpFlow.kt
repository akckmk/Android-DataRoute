package com.dataroute.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket

/** One app UDP "connection" (4-tuple) relayed over a connected [DatagramSocket]. */
internal class UdpFlow(
    private val engine: ForwardEngine,
    private val key: FlowKey,
    private val path: Path,
) {
    private companion object {
        const val DNS_IDLE_MS = 15_000L
        const val IDLE_MS = 120_000L
        const val MAX_PAYLOAD = 1500 - 28 // we can't fragment: anything bigger than the TUN MTU is dropped
    }

    private val socket: DatagramSocket = engine.upstream.openUdp(path, key.dstIp, key.dstPort)
    @Volatile private var closed = false
    @Volatile private var lastActivity = nowMs()

    private fun nowMs() = System.nanoTime() / 1_000_000

    fun start() {
        engine.executor.execute(::receiveLoop)
    }

    fun send(buf: ByteArray, off: Int, len: Int) {
        if (closed) return
        lastActivity = nowMs()
        try {
            socket.send(DatagramPacket(buf, off, len))
            engine.stats.up(path, len)
        } catch (e: Exception) {
            // e.g. ENETUNREACH while the network is flapping; UDP is lossy by contract, never let it reach the TUN loop
            engine.events("UDP $key via $path send failed: ${e.message}")
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(65535)
        val dp = DatagramPacket(buf, buf.size)
        while (!closed) {
            try {
                dp.setLength(buf.size)
                socket.receive(dp)
            } catch (e: IOException) {
                break
            }
            lastActivity = nowMs()
            val n = dp.length
            if (n > MAX_PAYLOAD) continue
            engine.stats.down(path, n)
            engine.sink.write(PacketBuilder.udp(key.dstIp, key.dstPort, key.clientIp, key.clientPort, buf, 0, n))
        }
        close()
    }

    fun tick(now: Long) {
        val limit = if (key.dstPort == 53) DNS_IDLE_MS else IDLE_MS
        if (now - lastActivity > limit) close()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        engine.removeUdp(key, this)
    }
}
