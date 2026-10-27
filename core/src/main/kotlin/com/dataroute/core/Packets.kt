package com.dataroute.core

const val PROTO_TCP = 6
const val PROTO_UDP = 17

const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.i32(i: Int): Int = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
internal fun ByteArray.put16(i: Int, v: Int) { this[i] = (v ushr 8).toByte(); this[i + 1] = v.toByte() }
internal fun ByteArray.put32(i: Int, v: Int) {
    this[i] = (v ushr 24).toByte(); this[i + 1] = (v ushr 16).toByte(); this[i + 2] = (v ushr 8).toByte(); this[i + 3] = v.toByte()
}

/** Sequence-number comparisons modulo 2^32. */
internal fun seqLt(a: Int, b: Int): Boolean = (a - b) < 0
internal fun seqLe(a: Int, b: Int): Boolean = (a - b) <= 0

/** A parsed IPv4 packet header. [buf] is only borrowed; copy anything you keep. */
class Ipv4(val buf: ByteArray, len: Int) {
    val valid: Boolean
    val headerLen: Int
    val totalLen: Int
    val protocol: Int
    val src: Int
    val dst: Int

    init {
        var ok = len >= 20 && (buf.u8(0) ushr 4) == 4
        headerLen = if (ok) (buf.u8(0) and 0x0F) * 4 else 0
        totalLen = if (ok) buf.u16(2) else 0
        if (ok && (headerLen < 20 || totalLen < headerLen || totalLen > len)) ok = false
        // Fragments can't be reassembled here; the TUN MTU keeps apps from producing them.
        if (ok && (buf.u16(6) and 0x3FFF) != 0) ok = false
        valid = ok
        protocol = if (ok) buf.u8(9) else 0
        src = if (ok) buf.i32(12) else 0
        dst = if (ok) buf.i32(16) else 0
    }
}

class TcpSegment(val ip: Ipv4) {
    private val b = ip.buf
    private val o = ip.headerLen
    val valid: Boolean = ip.totalLen - ip.headerLen >= 20 && ((b.u8(o + 12) ushr 4) * 4).let { it >= 20 && it <= ip.totalLen - ip.headerLen }
    val srcPort: Int get() = b.u16(o)
    val dstPort: Int get() = b.u16(o + 2)
    val seq: Int get() = b.i32(o + 4)
    val ack: Int get() = b.i32(o + 8)
    val dataOffset: Int get() = (b.u8(o + 12) ushr 4) * 4
    val flags: Int get() = b.u8(o + 13)
    val window: Int get() = b.u16(o + 14)
    val payloadOffset: Int get() = o + dataOffset
    val payloadLen: Int get() = ip.totalLen - ip.headerLen - dataOffset

    fun has(flag: Int): Boolean = (flags and flag) != 0

    /** MSS option from a SYN, or 0 if absent. */
    fun mssOption(): Int {
        var i = o + 20
        val end = o + dataOffset
        while (i < end) {
            when (val kind = b.u8(i)) {
                0 -> return 0
                1 -> i++
                else -> {
                    if (i + 1 >= end) return 0
                    val l = b.u8(i + 1)
                    if (l < 2) return 0
                    if (kind == 2 && l == 4 && i + 3 < end) return b.u16(i + 2)
                    i += l
                }
            }
        }
        return 0
    }
}

class UdpDatagram(val ip: Ipv4) {
    private val b = ip.buf
    private val o = ip.headerLen
    val valid: Boolean = ip.totalLen - ip.headerLen >= 8
    val srcPort: Int get() = b.u16(o)
    val dstPort: Int get() = b.u16(o + 2)
    val payloadOffset: Int get() = o + 8
    val payloadLen: Int get() = minOf(b.u16(o + 4), ip.totalLen - ip.headerLen) - 8
}

object PacketBuilder {
    private fun checksum(b: ByteArray, off: Int, len: Int, initial: Int = 0): Int {
        var sum = initial.toLong() and 0xFFFFFFFFL
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += b.u16(i); i += 2 }
        if (i < end) sum += b.u8(i) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    private fun pseudoSum(src: Int, dst: Int, proto: Int, len: Int): Int =
        (src ushr 16) + (src and 0xFFFF) + (dst ushr 16) + (dst and 0xFFFF) + proto + len

    private fun ipHeader(b: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        b[0] = 0x45
        b[1] = 0
        b.put16(2, total)
        b.put16(4, 0)
        b.put16(6, 0x4000) // DF
        b[8] = 64
        b[9] = proto.toByte()
        b.put16(10, 0)
        b.put32(12, src)
        b.put32(16, dst)
        b.put16(10, checksum(b, 0, 20))
    }

    /** Builds a complete IPv4+TCP packet. [mss] > 0 adds an MSS option (for SYN-ACK). */
    fun tcp(
        src: Int, srcPort: Int, dst: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray? = null, payloadOff: Int = 0, payloadLen: Int = 0, mss: Int = 0,
    ): ByteArray {
        val optLen = if (mss > 0) 4 else 0
        val tcpLen = 20 + optLen + payloadLen
        val total = 20 + tcpLen
        val b = ByteArray(total)
        ipHeader(b, total, PROTO_TCP, src, dst)
        val o = 20
        b.put16(o, srcPort); b.put16(o + 2, dstPort)
        b.put32(o + 4, seq); b.put32(o + 8, ack)
        b[o + 12] = (((20 + optLen) / 4) shl 4).toByte()
        b[o + 13] = flags.toByte()
        b.put16(o + 14, window.coerceIn(0, 0xFFFF))
        if (mss > 0) { b[o + 20] = 2; b[o + 21] = 4; b.put16(o + 22, mss) }
        if (payloadLen > 0) System.arraycopy(payload!!, payloadOff, b, o + 20 + optLen, payloadLen)
        b.put16(o + 16, checksum(b, o, tcpLen, pseudoSum(src, dst, PROTO_TCP, tcpLen)))
        return b
    }

    fun udp(src: Int, srcPort: Int, dst: Int, dstPort: Int, payload: ByteArray, off: Int, len: Int): ByteArray {
        val udpLen = 8 + len
        val total = 20 + udpLen
        val b = ByteArray(total)
        ipHeader(b, total, PROTO_UDP, src, dst)
        b.put16(20, srcPort); b.put16(22, dstPort); b.put16(24, udpLen)
        System.arraycopy(payload, off, b, 28, len)
        var c = checksum(b, 20, udpLen, pseudoSum(src, dst, PROTO_UDP, udpLen))
        if (c == 0) c = 0xFFFF
        b.put16(26, c)
        return b
    }

    /** Verifies the TCP/UDP checksum of a packet built here (used by tests). */
    fun verifyL4(b: ByteArray): Boolean {
        val ip = Ipv4(b, b.size)
        if (!ip.valid) return false
        val l4 = ip.totalLen - ip.headerLen
        return checksum(b, ip.headerLen, l4, pseudoSum(ip.src, ip.dst, ip.protocol, l4)) == 0 &&
            checksum(b, 0, ip.headerLen) == 0
    }
}
