package com.dataroute.core

/** Which physical network an upstream socket is bound to. */
enum class Path { WIFI, CELLULAR }

/** Decides, per destination address, which physical network a tunnelled connection leaves on. */
fun interface Router {
    fun pathFor(dstIp: Int): Path
}

/**
 * @param localNets destinations that live on the local (Wi-Fi) side: the NVR, the Wi-Fi subnet, VLANs...
 * @param forceCellular addresses that must go out cellular even if they sit inside [localNets]
 *   (the carrier's DNS servers are frequently in 10/8).
 * @param internetViaWifi when true, non-local traffic also uses Wi-Fi (Wi-Fi's own internet is healthy).
 */
class RoutePolicy(
    val localNets: List<Cidr>,
    val forceCellular: Set<Int> = emptySet(),
    val internetViaWifi: Boolean = false,
) : Router {
    override fun pathFor(dstIp: Int): Path {
        if (dstIp !in forceCellular && localNets.any { it.contains(dstIp) }) return Path.WIFI
        return if (internetViaWifi) Path.WIFI else Path.CELLULAR
    }

    companion object {
        val PRIVATE_RANGES: List<Cidr> = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16").map { Cidr.parse(it)!! }

        /** Never sent through the tunnel: multicast, reserved/broadcast, loopback, link-local. */
        val NEVER_TUNNEL: List<Cidr> =
            listOf("224.0.0.0/4", "240.0.0.0/4", "127.0.0.0/8", "169.254.0.0/16").map { Cidr.parse(it)!! }
    }
}

/** Turns the user's settings into the list of "local" prefixes. */
object LocalNets {
    class Result(val nets: List<Cidr>, val errors: List<String>)

    fun build(allPrivate: Boolean, wifiSubnet: Cidr?, nvrHost: String, extra: String): Result {
        val nets = LinkedHashSet<Cidr>()
        val errors = ArrayList<String>()
        if (allPrivate) nets += RoutePolicy.PRIVATE_RANGES
        if (wifiSubnet != null) nets += wifiSubnet
        val host = nvrHost.trim()
        if (host.isNotEmpty()) {
            val ip = Ip.parse(host)
            if (ip == null) errors += "NVR address must be an IPv4 address like 192.168.1.10" else nets += Cidr.of(ip, 32)
        }
        val (ok, bad) = Cidr.parseList(extra)
        nets += ok
        if (bad.isNotEmpty()) errors += "Not a valid subnet: ${bad.joinToString()}"
        return Result(nets.toList(), errors)
    }

    /** Parses "1.1.1.1, 8.8.8.8" into addresses, reporting rejected tokens. */
    fun parseDns(text: String): Pair<List<Int>, List<String>> {
        val ok = ArrayList<Int>(); val bad = ArrayList<String>()
        for (t in text.split(',', ' ', '\n', ';')) {
            if (t.isBlank()) continue
            val ip = Ip.parse(t)
            if (ip == null) bad += t.trim() else ok += ip
        }
        return ok to bad
    }
}
