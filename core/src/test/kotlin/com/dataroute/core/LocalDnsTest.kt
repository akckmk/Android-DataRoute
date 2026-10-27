package com.dataroute.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds a standard DNS query for [name]. */
internal fun query(name: String, type: Int = Dns.TYPE_A, id: Int = 0x1234): ByteArray {
    val o = ByteArrayOutputStream()
    o.write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
    for (label in name.split('.')) { o.write(label.length); o.write(label.toByteArray()) }
    o.write(0); o.write(type ushr 8); o.write(type); o.write(0); o.write(1)
    return o.toByteArray()
}

/** The A records in a reply (assumes the answer layout Dns.answer and simple servers produce). */
internal fun answers(reply: ByteArray): List<Int> {
    val q = Dns.parseQuestion(reply.copyOf().also { it[2] = (it[2].toInt() and 0x7F).toByte() }, reply.size)!!
    val n = reply.u16(6); var o = q.end; val out = ArrayList<Int>()
    repeat(n) {
        o += 2 // name pointer
        val type = reply.u16(o); val rdlen = reply.u16(o + 8)
        if (type == Dns.TYPE_A) out += reply.i32(o + 10)
        o += 10 + rdlen
    }
    return out
}

class LocalDnsTest {
    private val nas = Ip.parse("192.168.1.20")!!
    private val router = Ip.parse("192.168.1.1")!!

    @Test fun parsesQuestions() {
        val b = query("HomeAssistant.Local")
        assertNull(Dns.parseQuestion(b, b.size - 3)) // truncated query is rejected
        val p = Dns.parseQuestion(b, b.size)!!
        assertEquals("homeassistant.local", p.name); assertEquals(Dns.TYPE_A, p.type); assertEquals(b.size, p.end)
        assertNull(Dns.parseQuestion(ByteArray(10), 10))
    }

    @Test fun answersCarryTheAddress() {
        val b = query("nas.lan")
        val r = Dns.answer(b, Dns.parseQuestion(b, b.size)!!, listOf(nas))
        assertEquals(0x1234, Dns.id(r)); assertTrue((r.u8(2) and 0x80) != 0)
        assertEquals(listOf(nas), answers(r))
    }

    @Test fun hostsAndSuffixParsing() {
        val (hosts, bad) = LocalDns.parseHosts("nas = 192.168.1.20\n192.168.1.30 ha homeassistant\n# comment\nbroken line")
        assertEquals(mapOf("nas" to nas, "ha" to Ip.parse("192.168.1.30")!!, "homeassistant" to Ip.parse("192.168.1.30")!!), hosts)
        assertEquals(listOf("broken line"), bad)
        assertEquals(listOf("mydomain.net", "lab"), LocalDns.parseSuffixes(".mydomain.net, lab"))
    }

    @Test fun classifiesLookups() {
        val dns = LocalDns({ LocalDns.Settings(hosts = mapOf("plex" to nas), wifiDns = listOf(router)) }, { _, _, _, _, _ -> null })
        fun kind(n: String) = query(n).let { dns.plan(it, it.size)?.kind }
        assertEquals(LocalDns.Kind.HOSTS, kind("plex"))
        assertEquals(LocalDns.Kind.MDNS, kind("homeassistant.local"))
        assertEquals(LocalDns.Kind.WIFI_DNS, kind("nas.lan"))
        assertEquals(LocalDns.Kind.WIFI_DNS, kind("jellyfin.home.arpa"))
        assertEquals(LocalDns.Kind.WIFI_DNS, kind("unifi")) // single label
        assertEquals(LocalDns.Kind.WIFI_DNS, kind("20.1.168.192.in-addr.arpa"))
        assertNull(kind("claude.ai"))
        assertNull(kind("8.8.8.8.in-addr.arpa"))
        // without a Wi-Fi DNS server, router names go to the normal path instead of failing silently
        val noWifi = LocalDns({ LocalDns.Settings() }, { _, _, _, _, _ -> null })
        assertNull(query("nas.lan").let { noWifi.plan(it, it.size) })
    }

    @Test fun resolvesThroughTheRightTransport() {
        val calls = ConcurrentLinkedQueue<Triple<Path, Int, Int>>()
        val fakeReply = { q: ByteArray -> Dns.answer(q, Dns.parseQuestion(q, q.size)!!, listOf(nas)) }
        val dns = LocalDns({ LocalDns.Settings(wifiDns = listOf(router)) }) { path, server, port, q, _ -> calls += Triple(path, server, port); fakeReply(q) }
        val mq = query("synology.local")
        assertEquals(listOf(nas), answers(dns.resolve(dns.plan(mq, mq.size)!!, mq)))
        val wq = query("nas.lan")
        assertEquals(listOf(nas), answers(dns.resolve(dns.plan(wq, wq.size)!!, wq)))
        assertEquals(listOf(Triple(Path.WIFI, LocalDns.MDNS_GROUP, 5353), Triple(Path.WIFI, router, 53)), calls.toList())
    }

    @Test fun unansweredMdnsIsNxdomainNotATimeout() {
        val dns = LocalDns({ LocalDns.Settings() }) { _, _, _, _, _ -> null }
        val q = query("nothing.local")
        val r = dns.resolve(dns.plan(q, q.size)!!, q)
        assertEquals(Dns.RCODE_NXDOMAIN, r.u8(3) and 0x0F)
    }

    @Test fun transportTalksToARealServer() {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buf = ByteArray(512); val p = DatagramPacket(buf, buf.size)
            server.receive(p)
            val q = buf.copyOf(p.length)
            val r = Dns.answer(q, Dns.parseQuestion(q, q.size)!!, listOf(nas))
            server.send(DatagramPacket(r, r.size, p.socketAddress))
        }
        val up = object : Upstream {
            override fun openTcp(path: Path, dstIp: Int, dstPort: Int) = Socket()
            override fun openUdp(path: Path, dstIp: Int, dstPort: Int) = DatagramSocket()
            override fun openUdpRaw(path: Path) = DatagramSocket()
        }
        val r = UpstreamDnsTransport(up).exchange(Path.WIFI, Ip.parse("127.0.0.1")!!, server.localPort, query("nas.lan"), 2000)
        assertNotNull(r); assertEquals(listOf(nas), answers(r))
        server.close()
    }

    @Test fun engineAnswersLocalNamesAndForwardsTheRest() {
        val tun = Ip.parse("198.18.77.2")!!; val carrierDns = Ip.parse("10.177.0.34")!!
        val out = LinkedBlockingQueue<ByteArray>()
        val udpOpened = ConcurrentLinkedQueue<Int>()
        val up = object : Upstream {
            override fun openTcp(path: Path, dstIp: Int, dstPort: Int) = Socket()
            override fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket {
                udpOpened += dstIp
                return DatagramSocket().also { it.connect(InetAddress.getLoopbackAddress(), 9) }
            }
        }
        val dns = LocalDns({ LocalDns.Settings(hosts = mapOf("ha" to nas)) }) { _, _, _, _, _ -> null }
        val engine = ForwardEngine({ out.add(it) }, up, RoutePolicy(emptyList()), {}, dns)
        try {
            val q = query("ha")
            engine.onPacket(PacketBuilder.udp(tun, 40000, carrierDns, 53, q, 0, q.size).let { it }, 28 + q.size)
            val pkt = out.poll(3, TimeUnit.SECONDS)!!
            val ip = Ipv4(pkt, pkt.size); val d = UdpDatagram(ip)
            assertEquals(carrierDns, ip.src); assertEquals(53, d.srcPort); assertEquals(40000, d.dstPort)
            assertEquals(listOf(nas), answers(pkt.copyOfRange(d.payloadOffset, d.payloadOffset + d.payloadLen)))
            assertTrue(udpOpened.isEmpty(), "local answer never touched the carrier")
            val pub = query("claude.ai")
            engine.onPacket(PacketBuilder.udp(tun, 40001, carrierDns, 53, pub, 0, pub.size), 28 + pub.size)
            assertEquals(listOf(carrierDns), udpOpened.toList(), "public names still go to the carrier DNS")
        } finally {
            engine.stop()
        }
    }

    @Test fun publicNamesGoThroughTheNetworkResolver() {
        val tun = Ip.parse("198.18.77.2")!!; val fakeDns = Ip.parse("198.18.77.53")!!
        val out = LinkedBlockingQueue<ByteArray>()
        val asked = ConcurrentLinkedQueue<Pair<Path, String>>()
        val up = object : Upstream {
            override fun openTcp(path: Path, dstIp: Int, dstPort: Int) = Socket()
            override fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket = error("DNS must not be relayed as UDP")
        }
        val pub = PublicDns { path, q ->
            val name = Dns.parseQuestion(q, q.size)!!.name
            asked += path to name
            when (name) {
                "claude.ai" -> Dns.answer(q, Dns.parseQuestion(q, q.size)!!, listOf(Ip.parse("160.79.104.10")!!)).also { it[0] = 0x55; it[1] = 0x66 }
                "huge.example" -> Dns.answer(q, Dns.parseQuestion(q, q.size)!!, List(120) { i -> i })
                else -> null
            }
        }
        val engine = ForwardEngine({ out.add(it) }, up, RoutePolicy(emptyList()), {}, null, pub)
        fun ask(name: String, id: Int): ByteArray {
            val q = query(name, id = id)
            engine.onPacket(PacketBuilder.udp(tun, 41000, fakeDns, 53, q, 0, q.size), 28 + q.size)
            val pkt = out.poll(3, TimeUnit.SECONDS)!!
            val d = UdpDatagram(Ipv4(pkt, pkt.size))
            return pkt.copyOfRange(d.payloadOffset, d.payloadOffset + d.payloadLen)
        }
        try {
            val ok = ask("claude.ai", 0x1234)
            assertEquals(0x1234, Dns.id(ok), "reply keeps the app's query ID")
            assertEquals(listOf(Ip.parse("160.79.104.10")!!), answers(ok))
            val fail = ask("nowhere.example", 0x2222)
            assertEquals(Dns.RCODE_SERVFAIL, fail.u8(3) and 0x0F)
            val big = ask("huge.example", 0x3333)
            assertTrue((big.u8(2) and 0x02) != 0, "oversized answer is flagged as truncated")
            assertEquals(Path.CELLULAR, asked.first().first)
        } finally {
            engine.stop()
        }
    }
}
