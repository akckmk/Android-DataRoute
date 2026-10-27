package com.dataroute.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Just enough DNS wire format to read a query's question and to answer it. */
object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_OK = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    /** [name] is lower case without the trailing dot; [end] is the offset just past QTYPE/QCLASS. */
    class Question(val name: String, val type: Int, val end: Int)

    fun id(b: ByteArray): Int = b.u16(0)

    fun parseQuestion(b: ByteArray, len: Int): Question? {
        if (len < 17) return null
        if ((b.u8(2) and 0x80) != 0) return null // a response, not a query
        if (b.u16(4) < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= len) return null
            val l = b.u8(i)
            if (l == 0) { i++; break }
            if ((l and 0xC0) != 0 || i + 1 + l > len || sb.length + l > 253) return null // no compression in questions
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append(b[i + k].toInt().toChar())
            i += 1 + l
        }
        if (i + 4 > len) return null
        return Question(sb.toString().lowercase(), b.u16(i), i + 4)
    }

    /** An empty reply with the TC bit set: "too big for UDP". */
    fun truncated(query: ByteArray, q: Question): ByteArray =
        answer(query, q, emptyList()).also { it[2] = (it.u8(2) or 0x02).toByte() }

    /** A reply to [query] carrying [ips] as A records (an empty list = NOERROR with no data). */
    fun answer(query: ByteArray, q: Question, ips: List<Int>, rcode: Int = RCODE_OK, ttl: Int = 60): ByteArray {
        val out = ByteArray(q.end + ips.size * 16)
        System.arraycopy(query, 0, out, 0, q.end)
        val rd = query.u8(2) and 0x01
        out[2] = (0x80 or (query.u8(2) and 0x78) or rd).toByte() // QR, opcode, RD
        out[3] = (0x80 or (rcode and 0x0F)).toByte() // RA + rcode
        out.put16(4, 1); out.put16(6, ips.size); out.put16(8, 0); out.put16(10, 0) // drop EDNS etc.
        var o = q.end
        for (ip in ips) {
            out.put16(o, 0xC00C); out.put16(o + 2, TYPE_A); out.put16(o + 4, 1)
            out.put32(o + 6, ttl); out.put16(o + 10, 4); out.put32(o + 12, ip)
            o += 16
        }
        return out
    }
}

/** Sends one DNS message and waits for the reply with the same ID. */
fun interface DnsTransport {
    fun exchange(path: Path, server: Int, port: Int, query: ByteArray, timeoutMs: Int): ByteArray?
}

/** [DnsTransport] over unconnected sockets from [Upstream] (unconnected so mDNS replies from any host are accepted). */
class UpstreamDnsTransport(private val upstream: Upstream) : DnsTransport {
    override fun exchange(path: Path, server: Int, port: Int, query: ByteArray, timeoutMs: Int): ByteArray? {
        val s = try { upstream.openUdpRaw(path) } catch (e: IOException) { return null }
        s.use {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            try {
                it.send(DatagramPacket(query, query.size, InetAddress.getByAddress(Ip.toBytes(server)), port))
                val buf = ByteArray(4096)
                while (true) {
                    val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                    if (left <= 0) return null
                    it.soTimeout = left
                    val p = DatagramPacket(buf, buf.size)
                    it.receive(p)
                    if (p.length >= 12 && buf.u16(0) == Dns.id(query) && (buf.u8(2) and 0x80) != 0) return buf.copyOf(p.length)
                }
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: IOException) {
                return null
            }
        }
    }
}

/**
 * Answers the lookups the carrier's DNS can't: names that only exist on the home network.
 *  - entries in the user's hosts list,
 *  - `*.local` (multicast DNS on the Wi-Fi: homeassistant.local, synology.local…),
 *  - single-word names and local domains (`nas.lan`, `plex.home`, the router's own domain) via the Wi-Fi's DNS server,
 *  - reverse lookups of private addresses.
 * Everything else returns null from [plan] and is forwarded normally.
 */
class LocalDns(private val settings: () -> Settings, private val transport: DnsTransport) {
    class Settings(
        val hosts: Map<String, Int> = emptyMap(),
        val localSuffixes: List<String> = DEFAULT_SUFFIXES,
        val wifiDns: List<Int> = emptyList(),
    )

    enum class Kind { HOSTS, MDNS, WIFI_DNS }

    class Plan(val kind: Kind, val question: Dns.Question, val ip: Int = 0)

    companion object {
        val DEFAULT_SUFFIXES = listOf("lan", "home", "internal", "home.arpa", "localdomain", "localnet", "intranet")
        val MDNS_GROUP: Int = Ip.parse("224.0.0.251")!!
        const val MDNS_PORT = 5353
        const val MDNS_TIMEOUT_MS = 1500
        const val DNS_TIMEOUT_MS = 2500

        /** Parses "nas = 192.168.1.20" / "192.168.1.20 nas" lines; returns hosts and rejected lines. */
        fun parseHosts(text: String): Pair<Map<String, Int>, List<String>> {
            val out = LinkedHashMap<String, Int>(); val bad = ArrayList<String>()
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split('=', ' ', '\t', ',').map { it.trim() }.filter { it.isNotEmpty() }
                val ipTok = parts.firstOrNull { Ip.parse(it) != null }
                val names = parts.filter { it != ipTok }
                if (ipTok == null || names.isEmpty() || names.any { !validName(it) }) { bad += raw.trim(); continue }
                for (n in names) out[n.lowercase().trimEnd('.')] = Ip.parse(ipTok)!!
            }
            return out to bad
        }

        fun parseSuffixes(text: String): List<String> =
            text.split(',', ' ', '\n', ';').map { it.trim().trim('.').lowercase() }.filter { it.isNotEmpty() && validName(it) }

        private fun validName(s: String) = s.length in 1..253 && s.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }

        private fun isReversePrivate(name: String): Boolean {
            if (!name.endsWith(".in-addr.arpa")) return false
            val o = name.removeSuffix(".in-addr.arpa").split('.').reversed().mapNotNull { it.toIntOrNull() }
            return when {
                o.isEmpty() -> false
                o[0] == 10 -> true
                o[0] == 192 && o.getOrNull(1) == 168 -> true
                o[0] == 172 && (o.getOrNull(1) ?: -1) in 16..31 -> true
                o[0] == 169 && o.getOrNull(1) == 254 -> true
                else -> false
            }
        }
    }

    /** Cheap, no I/O: decides whether this query is for a local name, and how to answer it. */
    fun plan(query: ByteArray, len: Int): Plan? {
        val q = Dns.parseQuestion(query, len) ?: return null
        val s = settings()
        val name = q.name
        if (name.isEmpty()) return null
        s.hosts[name]?.let { return Plan(Kind.HOSTS, q, it) }
        if (name == "local" || name.endsWith(".local")) return Plan(Kind.MDNS, q)
        val local = isReversePrivate(name) || !name.contains('.') ||
            s.localSuffixes.any { name == it || name.endsWith(".$it") }
        if (!local) return null
        return if (s.wifiDns.isNotEmpty()) Plan(Kind.WIFI_DNS, q) else null
    }

    /** Blocking: produces the reply for a planned query (never null for a valid plan). */
    fun resolve(plan: Plan, query: ByteArray): ByteArray {
        val q = plan.question
        return when (plan.kind) {
            Kind.HOSTS -> Dns.answer(query, q, if (q.type == Dns.TYPE_A) listOf(plan.ip) else emptyList())
            Kind.MDNS -> transport.exchange(Path.WIFI, MDNS_GROUP, MDNS_PORT, query, MDNS_TIMEOUT_MS)
                ?: Dns.answer(query, q, emptyList(), Dns.RCODE_NXDOMAIN)
            Kind.WIFI_DNS -> {
                for (server in settings().wifiDns) {
                    transport.exchange(Path.WIFI, server, 53, query, DNS_TIMEOUT_MS)?.let { return it }
                }
                Dns.answer(query, q, emptyList(), Dns.RCODE_SERVFAIL)
            }
        }
    }
}
