package com.dataroute.core

import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hostile input: whatever any app on the phone can push into the tunnel must never take the engine down. */
class AdversarialTest {
    private val tun = Ip.parse("198.18.77.2")!!
    private val engines = ArrayList<ForwardEngine>()
    @AfterTest fun cleanup() { engines.forEach { it.stop() } }

    /** Upstream that refuses everything immediately, so fuzzing exercises parsing, not networking. */
    private val refusing = object : Upstream {
        override fun openTcp(path: Path, dstIp: Int, dstPort: Int): Socket = throw IOException("refused")
        override fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket = throw IOException("refused")
        override fun openUdpRaw(path: Path): DatagramSocket = throw IOException("refused")
    }

    private fun engine(pub: PublicDns? = null, dns: LocalDns? = null, sink: PacketSink = PacketSink { }) =
        ForwardEngine(sink, refusing, RoutePolicy(listOf(Cidr.parse("192.168.0.0/16")!!)), {}, dns, pub).also { engines += it }

    private fun validSamples(rnd: Random): List<ByteArray> {
        val dst = Ip.parse("192.168.1.10")!!
        val q = query("homeassistant.local")
        return listOf(
            PacketBuilder.tcp(tun, 40000, dst, 443, rnd.nextInt(), 0, TCP_SYN, 65535, mss = 1460),
            PacketBuilder.tcp(tun, 40000, dst, 443, rnd.nextInt(), rnd.nextInt(), TCP_ACK or TCP_PSH, 65535, ByteArray(100), 0, 100),
            PacketBuilder.tcp(tun, 40001, dst, 443, 1, 1, TCP_FIN or TCP_ACK, 0),
            PacketBuilder.udp(tun, 5353, Ip.parse("198.18.77.53")!!, 53, q, 0, q.size),
            PacketBuilder.udp(tun, 5000, dst, 9999, ByteArray(30), 0, 30),
        )
    }

    @Test fun randomAndMutatedPacketsNeverThrow() {
        val rnd = Random(1234)
        val dns = LocalDns({ LocalDns.Settings(hosts = mapOf("nas" to 1), wifiDns = listOf(Ip.parse("192.168.1.1")!!)) }) { _, _, _, _, _ -> null }
        val e = engine(pub = { _, _ -> null }, dns = dns)
        val samples = validSamples(rnd)
        repeat(150_000) { i ->
            val pkt: ByteArray = if (i % 3 == 0) {
                ByteArray(rnd.nextInt(0, 1600)).also { rnd.nextBytes(it) }
            } else {
                val base = samples[rnd.nextInt(samples.size)].copyOf()
                repeat(rnd.nextInt(1, 8)) { base[rnd.nextInt(base.size)] = rnd.nextInt(256).toByte() }
                if (rnd.nextInt(5) == 0) base.copyOf(rnd.nextInt(0, base.size + 40)) else base
            }
            // The contract: never throw, whatever the bytes (the TUN loop must survive).
            e.onPacket(pkt, rnd.nextInt(0, pkt.size + 1))
        }
    }

    @Test fun dnsParsersSurviveGarbage() {
        val rnd = Random(99)
        val dns = LocalDns({ LocalDns.Settings(wifiDns = listOf(1)) }) { _, _, _, _, _ -> null }
        repeat(200_000) {
            val b = if (it % 2 == 0) ByteArray(rnd.nextInt(0, 600)).also { a -> rnd.nextBytes(a) }
            else query("a".repeat(rnd.nextInt(1, 63)) + ".lan").also { q -> repeat(3) { q[rnd.nextInt(q.size)] = rnd.nextInt(256).toByte() } }
            val len = rnd.nextInt(0, b.size + 1)
            Dns.parseQuestion(b, len)
            dns.plan(b, len)?.let { p -> dns.resolve(p, b) }
        }
    }

    @Test fun namesInLogsAreSanitized() {
        val events = ConcurrentLinkedQueue<String>()
        // A query whose name carries a newline and a forged log line.
        val crafted = ByteArray(12) + byteArrayOf(19) + "x\n12:00:00  TCP fak".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        crafted[0] = 0x12; crafted[1] = 0x34; crafted[2] = 0x01; crafted[5] = 1
        val e = ForwardEngine({ }, refusing, RoutePolicy(emptyList()), { events += it }, null) { _, _ -> null }
        engines += e
        e.onPacket(PacketBuilder.udp(tun, 5000, Ip.parse("198.18.77.53")!!, 53, crafted, 0, crafted.size), 28 + crafted.size)
        assertTrue(waitFor(3000) { events.isNotEmpty() })
        assertFalse(events.any { it.contains('\n') }, "log line contains a raw newline: ${events.toList()}")
    }

    @Test fun dnsFloodIsBoundedNotUnlimitedThreads() {
        val inFlight = AtomicInteger(); val peak = AtomicInteger()
        val release = CountDownLatch(1)
        val slowDns = PublicDns { _, q ->
            val now = inFlight.incrementAndGet(); peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try { release.await(5, TimeUnit.SECONDS) } finally { inFlight.decrementAndGet() }
            Dns.answer(q, Dns.parseQuestion(q, q.size)!!, emptyList())
        }
        val e = engine(pub = slowDns)
        repeat(3000) { i ->
            val q = query("host$i.example", id = i)
            e.onPacket(PacketBuilder.udp(tun, 10000 + (i % 5000), Ip.parse("198.18.77.53")!!, 53, q, 0, q.size), 28 + q.size)
        }
        Thread.sleep(500)
        val p = peak.get()
        release.countDown()
        assertTrue(p <= ForwardEngine.MAX_DNS_IN_FLIGHT, "peak concurrent DNS workers $p > cap ${ForwardEngine.MAX_DNS_IN_FLIGHT}")
        assertTrue(p > 0)
    }

    @Test fun manyConcurrentConnectionsAreAllServed() {
        // 400 apps' connections at once, each echoing through the engine: none lost, none mixed up.
        val ss = java.net.ServerSocket(0)
        val acceptor = Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { return@Thread }
                Thread { s.use { val b = ByteArray(4096); while (true) { val n = it.getInputStream().read(b); if (n < 0) break; it.getOutputStream().write(b, 0, n) } } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }
        val up = object : Upstream {
            override fun openTcp(path: Path, dstIp: Int, dstPort: Int) =
                Socket().also { it.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), ss.localPort), 3000) }
            override fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket = throw IOException("n/a")
        }
        val inbox = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.LinkedBlockingQueue<ByteArray>>()
        val e = ForwardEngine({ pkt ->
            val ip = Ipv4(pkt, pkt.size)
            if (ip.valid && ip.protocol == PROTO_TCP) inbox[TcpSegment(ip).dstPort]?.add(pkt)
        }, up, RoutePolicy(emptyList()))
        engines += e
        val dst = Ip.parse("93.184.216.34")!!
        val n = 400
        val ok = AtomicInteger()
        val threads = (0 until n).map { i ->
            val port = 20000 + i
            inbox[port] = java.util.concurrent.LinkedBlockingQueue()
            Thread {
                val q = inbox[port]!!
                val isn = 1000 * i
                e.onPacket(PacketBuilder.tcp(tun, port, dst, 80, isn, 0, TCP_SYN, 65535, mss = 1460).let { it }, 44)
                val synAck = q.poll(10, TimeUnit.SECONDS) ?: return@Thread
                val sa = TcpSegment(Ipv4(synAck, synAck.size))
                if (!sa.has(TCP_SYN)) return@Thread
                val msg = "hello-$i".toByteArray()
                val data = PacketBuilder.tcp(tun, port, dst, 80, isn + 1, sa.seq + 1, TCP_ACK or TCP_PSH, 65535, msg, 0, msg.size)
                e.onPacket(data, data.size)
                val deadline = System.nanoTime() + 10_000_000_000L
                while (System.nanoTime() < deadline) {
                    val p = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    val s = TcpSegment(Ipv4(p, p.size))
                    if (s.payloadLen > 0) {
                        if (String(p, s.payloadOffset, s.payloadLen) == "hello-$i") ok.incrementAndGet()
                        break
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(30_000) }
        ss.close(); acceptor.interrupt()
        assertEquals(n, ok.get(), "every connection got its own echo back")
    }

    private fun waitFor(ms: Long, c: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (c()) return true; Thread.sleep(20) }
        return c()
    }
}
