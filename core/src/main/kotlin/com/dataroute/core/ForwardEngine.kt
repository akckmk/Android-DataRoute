package com.dataroute.core

import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where packets that should arrive at the apps (the TUN device) are written. Must be thread safe. */
fun interface PacketSink {
    fun write(packet: ByteArray)
}

/** Opens sockets on the physical network that corresponds to a [Path]. */
interface Upstream {
    /** Returns a *connected* socket (blocking until connected or throwing). */
    @Throws(IOException::class)
    fun openTcp(path: Path, dstIp: Int, dstPort: Int): Socket

    /** Returns a UDP socket already connect()ed to the destination. */
    @Throws(IOException::class)
    fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket
}

data class FlowKey(val clientIp: Int, val clientPort: Int, val dstIp: Int, val dstPort: Int) {
    override fun toString() = "${Ip.format(dstIp)}:$dstPort"
}

class Stats {
    val wifiUp = AtomicLong()
    val wifiDown = AtomicLong()
    val cellUp = AtomicLong()
    val cellDown = AtomicLong()
    val tcpOpened = AtomicLong()
    val tcpFailed = AtomicLong()
    val activeTcp = AtomicInteger()
    val activeUdp = AtomicInteger()

    fun up(p: Path, n: Int) { (if (p == Path.WIFI) wifiUp else cellUp).addAndGet(n.toLong()) }
    fun down(p: Path, n: Int) { (if (p == Path.WIFI) wifiDown else cellDown).addAndGet(n.toLong()) }
}

/**
 * A tiny user-space NAT: reads raw IPv4 packets that apps wrote into the TUN device, terminates their TCP
 * connections / relays their UDP datagrams over real sockets obtained from [Upstream], and writes the replies
 * back to [sink] as synthesized IPv4 packets.
 */
class ForwardEngine(
    internal val sink: PacketSink,
    internal val upstream: Upstream,
    internal val router: Router,
    internal val events: (String) -> Unit = {},
) {
    companion object {
        const val MSS = 1400
        const val MAX_TCP_FLOWS = 1024
        const val MAX_UDP_FLOWS = 1024
    }

    val stats = Stats()

    internal val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "dr-flow").apply { isDaemon = true }
    }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dr-timer").apply { isDaemon = true }
    }
    private val tcp = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val udp = ConcurrentHashMap<FlowKey, UdpFlow>()
    @Volatile private var stopped = false

    init {
        timer.scheduleWithFixedDelay({ runCatching { tick() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /** Feed one packet read from the TUN device. [buf] may be reused by the caller afterwards. */
    fun onPacket(buf: ByteArray, len: Int) {
        if (stopped) return
        val ip = Ipv4(buf, len)
        if (!ip.valid) return
        when (ip.protocol) {
            PROTO_TCP -> handleTcp(ip)
            PROTO_UDP -> handleUdp(ip)
            else -> Unit // ICMP etc. are not forwarded
        }
    }

    private fun handleTcp(ip: Ipv4) {
        val seg = TcpSegment(ip)
        if (!seg.valid) return
        val key = FlowKey(ip.src, seg.srcPort, ip.dst, seg.dstPort)
        val flow = tcp[key]
        if (seg.has(TCP_SYN) && !seg.has(TCP_ACK)) {
            if (flow != null) { flow.onDuplicateSyn(); return }
            if (tcp.size >= MAX_TCP_FLOWS) { resetFor(ip, seg); return }
            val f = TcpFlow(this, key, seg.seq, seg.mssOption(), seg.window)
            if (tcp.putIfAbsent(key, f) == null) {
                stats.activeTcp.incrementAndGet()
                executor.execute(f::run)
            }
            return
        }
        if (flow == null) {
            if (!seg.has(TCP_RST)) resetFor(ip, seg)
            return
        }
        flow.onSegment(seg)
    }

    /** RFC 793 reset for a segment that belongs to no connection. */
    private fun resetFor(ip: Ipv4, seg: TcpSegment) {
        val pkt = if (seg.has(TCP_ACK)) {
            PacketBuilder.tcp(ip.dst, seg.dstPort, ip.src, seg.srcPort, seg.ack, 0, TCP_RST, 0)
        } else {
            val consumed = seg.payloadLen + (if (seg.has(TCP_SYN)) 1 else 0) + (if (seg.has(TCP_FIN)) 1 else 0)
            PacketBuilder.tcp(ip.dst, seg.dstPort, ip.src, seg.srcPort, 0, seg.seq + consumed, TCP_RST or TCP_ACK, 0)
        }
        sink.write(pkt)
    }

    private fun handleUdp(ip: Ipv4) {
        val d = UdpDatagram(ip)
        if (!d.valid || d.payloadLen < 0) return
        val key = FlowKey(ip.src, d.srcPort, ip.dst, d.dstPort)
        var flow = udp[key]
        if (flow == null) {
            if (udp.size >= MAX_UDP_FLOWS) return
            val created = try {
                UdpFlow(this, key, router.pathFor(ip.dst))
            } catch (e: IOException) {
                events("UDP ${key} failed: ${e.message}")
                return
            }
            val prev = udp.putIfAbsent(key, created)
            if (prev == null) {
                stats.activeUdp.incrementAndGet()
                created.start()
                flow = created
            } else {
                created.close()
                flow = prev
            }
        }
        flow.send(ip.buf, d.payloadOffset, d.payloadLen)
    }

    internal fun removeTcp(key: FlowKey, f: TcpFlow) {
        if (tcp.remove(key, f)) stats.activeTcp.decrementAndGet()
    }

    internal fun removeUdp(key: FlowKey, f: UdpFlow) {
        if (udp.remove(key, f)) stats.activeUdp.decrementAndGet()
    }

    private fun tick() {
        val now = System.nanoTime() / 1_000_000
        for (f in tcp.values) f.tick(now)
        for (f in udp.values) f.tick(now)
    }

    fun stop() {
        stopped = true
        timer.shutdownNow()
        for (f in tcp.values.toList()) f.shutdown()
        for (f in udp.values.toList()) f.close()
        executor.shutdownNow()
    }
}
