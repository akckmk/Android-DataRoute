package com.dataroute.core

/** IPv4 helpers. Addresses are plain [Int]s in network byte order semantics (big-endian value). */
object Ip {
    fun parse(s: String): Int? {
        val parts = s.trim().split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            v = (v shl 8) or n
        }
        return v
    }

    fun format(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun fromBytes(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    fun toBytes(ip: Int): ByteArray =
        byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())
}

/** An IPv4 prefix. [network] always has its host bits cleared. */
class Cidr private constructor(val network: Int, val prefix: Int) {
    val mask: Int get() = maskOf(prefix)

    fun contains(ip: Int): Boolean = (ip and mask) == network

    fun overlaps(o: Cidr): Boolean {
        val m = maskOf(minOf(prefix, o.prefix))
        return (network and m) == (o.network and m)
    }

    /** True if every address of [o] is inside this prefix. */
    fun covers(o: Cidr): Boolean = prefix <= o.prefix && contains(o.network)

    override fun toString(): String = "${Ip.format(network)}/$prefix"
    override fun equals(other: Any?): Boolean = other is Cidr && other.network == network && other.prefix == prefix
    override fun hashCode(): Int = network * 31 + prefix

    companion object {
        private fun maskOf(prefix: Int): Int = if (prefix == 0) 0 else -1 shl (32 - prefix)

        fun of(ip: Int, prefix: Int): Cidr {
            require(prefix in 0..32) { "bad prefix $prefix" }
            return Cidr(ip and maskOf(prefix), prefix)
        }

        /** Accepts "a.b.c.d" (treated as /32) or "a.b.c.d/n". Host bits are masked off. */
        fun parse(text: String): Cidr? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val slash = t.indexOf('/')
            val ip = Ip.parse(if (slash < 0) t else t.substring(0, slash)) ?: return null
            val prefix = if (slash < 0) 32 else t.substring(slash + 1).toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            return of(ip, prefix)
        }

        /** Parses a comma/space/newline separated list; returns the valid entries and the rejected tokens. */
        fun parseList(text: String): Pair<List<Cidr>, List<String>> {
            val ok = ArrayList<Cidr>()
            val bad = ArrayList<String>()
            for (tok in text.split(',', ' ', '\n', '\t', ';')) {
                if (tok.isBlank()) continue
                val c = parse(tok)
                if (c == null) bad += tok.trim() else ok += c
            }
            return ok to bad
        }

        /**
         * The set of prefixes that exactly cover 0.0.0.0/0 minus every prefix in [excluded].
         * Used to build VPN routes that leave some destinations out of the tunnel on any Android
         * version (VpnService.Builder.excludeRoute only exists on API 33+).
         */
        fun complement(excluded: Collection<Cidr>): List<Cidr> {
            val out = ArrayList<Cidr>()
            fun walk(block: Cidr, ex: List<Cidr>) {
                val rel = ex.filter { it.overlaps(block) }
                if (rel.isEmpty()) { out += block; return }
                if (rel.any { it.covers(block) }) return
                // Some exclusion lies strictly inside this block (so prefix < 32): split in two.
                val p = block.prefix + 1
                walk(Cidr(block.network, p), rel)
                walk(Cidr(block.network or (1 shl (32 - p)), p), rel)
            }
            walk(Cidr(0, 0), excluded.toList())
            return out
        }
    }
}
