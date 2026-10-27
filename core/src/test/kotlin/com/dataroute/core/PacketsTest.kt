package com.dataroute.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PacketsTest {
    private val a = Ip.parse("198.18.77.2")!!
    private val b = Ip.parse("192.168.1.10")!!

    @Test fun tcpRoundTrip() {
        val payload = ByteArray(1001) { it.toByte() } // odd length exercises checksum padding
        val pkt = PacketBuilder.tcp(a, 40000, b, 443, 0x7FFFFFF0, -5, TCP_ACK or TCP_PSH, 65535, payload, 0, payload.size)
        assertTrue(PacketBuilder.verifyL4(pkt))
        val ip = Ipv4(pkt, pkt.size)
        assertTrue(ip.valid); assertEquals(PROTO_TCP, ip.protocol); assertEquals(a, ip.src); assertEquals(b, ip.dst)
        val t = TcpSegment(ip)
        assertTrue(t.valid)
        assertEquals(40000, t.srcPort); assertEquals(443, t.dstPort)
        assertEquals(0x7FFFFFF0, t.seq); assertEquals(-5, t.ack)
        assertTrue(t.has(TCP_PSH)); assertFalse(t.has(TCP_SYN))
        assertEquals(1001, t.payloadLen)
        assertContentEquals(payload, pkt.copyOfRange(t.payloadOffset, t.payloadOffset + t.payloadLen))
    }

    @Test fun synWithMss() {
        val pkt = PacketBuilder.tcp(a, 1, b, 2, 100, 0, TCP_SYN, 1000, mss = 1360)
        assertTrue(PacketBuilder.verifyL4(pkt))
        val t = TcpSegment(Ipv4(pkt, pkt.size))
        assertEquals(1360, t.mssOption())
        assertEquals(0, t.payloadLen)
    }

    @Test fun udpRoundTrip() {
        val payload = "hello dns".toByteArray()
        val pkt = PacketBuilder.udp(a, 5353, b, 53, payload, 0, payload.size)
        assertTrue(PacketBuilder.verifyL4(pkt))
        val d = UdpDatagram(Ipv4(pkt, pkt.size))
        assertEquals(5353, d.srcPort); assertEquals(53, d.dstPort); assertEquals(payload.size, d.payloadLen)
        assertContentEquals(payload, pkt.copyOfRange(d.payloadOffset, d.payloadOffset + d.payloadLen))
    }

    @Test fun rejectsGarbageAndFragments() {
        assertFalse(Ipv4(ByteArray(10), 10).valid)
        assertFalse(Ipv4(ByteArray(40), 40).valid) // version 0
        val pkt = PacketBuilder.udp(a, 1, b, 2, ByteArray(4), 0, 4)
        pkt[6] = 0x20 // MF flag
        assertFalse(Ipv4(pkt, pkt.size).valid)
        val short = PacketBuilder.udp(a, 1, b, 2, ByteArray(4), 0, 4)
        assertFalse(Ipv4(short, short.size - 1).valid) // truncated vs total length
    }

    @Test fun seqCompareWraps() {
        assertTrue(seqLt(-10, 10)); assertTrue(seqLt(Int.MAX_VALUE, Int.MIN_VALUE)); assertFalse(seqLt(5, 5)); assertTrue(seqLe(5, 5))
    }
}
