package com.dataroute.app

import android.content.Context
import com.dataroute.core.Cidr
import com.dataroute.core.Ip
import com.dataroute.core.LocalDns
import com.dataroute.core.LocalNets
import org.json.JSONArray
import org.json.JSONObject

enum class LocalMode { TUNNEL, NATIVE }

/** The kinds of home services people reach locally, with their usual port, web path and Android app. */
enum class ServiceKind(
    val label: String,
    val blurb: String,
    val port: Int,
    val scheme: String,
    val path: String,
    val appPackage: String?,
) {
    PROTECT("UniFi Protect", "Cameras and recordings", 443, "https", "/protect", "com.ubnt.unifi.protect"),
    HOME_ASSISTANT("Home Assistant", "Smart-home dashboard", 8123, "http", "", "io.homeassistant.companion.android"),
    PLEX("Plex", "Movies, shows and music", 32400, "http", "/web", "com.plexapp.android"),
    JELLYFIN("Jellyfin", "Your own media server", 8096, "http", "", "org.jellyfin.mobile"),
    NAS("NAS", "Synology, QNAP, TrueNAS, Unraid", 5001, "https", "", null),
    OTHER("Other", "Pi-hole, Frigate, Nextcloud, printers…", 80, "http", "", null),
}

data class HomeService(val id: String, val kind: ServiceKind, val name: String, val host: String, val port: Int) {
    val address: String get() = "$host:$port"
    fun url(): String {
        val defaultPort = if (kind.scheme == "https") 443 else 80
        return "${kind.scheme}://$host${if (port == defaultPort) "" else ":$port"}${kind.path}"
    }

    companion object {
        /** An IPv4 address or a host name such as homeassistant.local or nas.lan. */
        fun validHost(h: String): Boolean {
            val t = h.trim()
            if (t.isEmpty() || t.length > 253) return false
            if (t.all { it.isDigit() || it == '.' }) return Ip.parse(t) != null
            return t.split('.').all { l -> l.isNotEmpty() && l.length <= 63 && l.all { it.isLetterOrDigit() || it == '-' } && !l.startsWith('-') }
        }
    }
}
enum class InternetMode { AUTO, ALWAYS_CELLULAR }

data class Config(
    /** IPv4 address of the UniFi Protect NVR / console, e.g. 192.168.1.10. */
    val nvrHost: String = "",
    /** Treat 10/8, 172.16/12 and 192.168/16 as "local" (reachable over Wi-Fi). Covers multiple VLANs. */
    val allPrivateIsLocal: Boolean = true,
    /** Also treat the subnet of the connected Wi-Fi as local. */
    val autoWifiSubnet: Boolean = true,
    /** Extra local subnets, comma or newline separated (CIDR or single IPs). */
    val extraLocal: String = "",
    val internetMode: InternetMode = InternetMode.AUTO,
    val localMode: LocalMode = LocalMode.TUNNEL,
    val useCarrierDns: Boolean = true,
    val customDns: String = "1.1.1.1, 8.8.8.8",
    /** Packages that never enter the tunnel (apps that dislike VPNs). */
    val bypassApps: Set<String> = emptySet(),
    /** Accept self-signed certificates in the built-in viewer, for local devices only. */
    val acceptNvrCert: Boolean = true,
    /** Resolve home names (homeassistant.local, nas.lan, the router's names) over Wi-Fi. */
    val localNames: Boolean = true,
    /** "nas = 192.168.1.20" lines: names you want to work even without a local DNS. */
    val localHosts: String = "",
    /** Extra local domains besides .local/.lan/.home/.internal/.home.arpa. */
    val localDomains: String = "",
    val services: List<HomeService> = emptyList(),
) {
    /** Human readable problems with the current values; empty when valid. */
    fun errors(): List<String> {
        val out = ArrayList<String>()
        out += LocalNets.build(allPrivateIsLocal, null, nvrHost, extraLocal).errors
        LocalDns.parseHosts(localHosts).second.takeIf { it.isNotEmpty() }?.let {
            out += "Hosts lines must look like \"nas = 192.168.1.20\": ${it.joinToString()}"
        }
        if (!useCarrierDns) {
            val (ok, bad) = LocalNets.parseDns(customDns)
            if (bad.isNotEmpty()) out += "Not a valid DNS server: ${bad.joinToString()}"
            else if (ok.isEmpty()) out += "Enter at least one DNS server or use the carrier's"
        }
        return out
    }

    /** Local prefixes: settings, plus every service and hosts entry given as an address. */
    fun localNets(wifiSubnet: Cidr?): List<Cidr> {
        val known = services.map { it.host }.filter { Ip.parse(it) != null } +
            LocalDns.parseHosts(localHosts).first.values.map { Ip.format(it) }
        val extra = (listOf(extraLocal) + known).joinToString(",")
        return LocalNets.build(allPrivateIsLocal, if (autoWifiSubnet) wifiSubnet else null, nvrHost, extra).nets
    }
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("dataroute", Context.MODE_PRIVATE)

    fun load(): Config {
        val d = Config()
        return Config(
            nvrHost = sp.getString("nvrHost", d.nvrHost) ?: d.nvrHost,
            allPrivateIsLocal = sp.getBoolean("allPrivateIsLocal", d.allPrivateIsLocal),
            autoWifiSubnet = sp.getBoolean("autoWifiSubnet", d.autoWifiSubnet),
            extraLocal = sp.getString("extraLocal", d.extraLocal) ?: d.extraLocal,
            internetMode = enumOr("internetMode", d.internetMode),
            localMode = enumOr("localMode", d.localMode),
            useCarrierDns = sp.getBoolean("useCarrierDns", d.useCarrierDns),
            customDns = sp.getString("customDns", d.customDns) ?: d.customDns,
            bypassApps = sp.getStringSet("bypassApps", emptySet())?.toSet() ?: emptySet(),
            acceptNvrCert = sp.getBoolean("acceptNvrCert", d.acceptNvrCert),
            localNames = sp.getBoolean("localNames", d.localNames),
            localHosts = sp.getString("localHosts", d.localHosts) ?: d.localHosts,
            localDomains = sp.getString("localDomains", d.localDomains) ?: d.localDomains,
            services = decodeServices(sp.getString("services", null)),
        )
    }

    fun save(c: Config) {
        sp.edit()
            .putString("nvrHost", c.nvrHost.trim())
            .putBoolean("allPrivateIsLocal", c.allPrivateIsLocal)
            .putBoolean("autoWifiSubnet", c.autoWifiSubnet)
            .putString("extraLocal", c.extraLocal)
            .putString("internetMode", c.internetMode.name)
            .putString("localMode", c.localMode.name)
            .putBoolean("useCarrierDns", c.useCarrierDns)
            .putString("customDns", c.customDns)
            .putStringSet("bypassApps", c.bypassApps)
            .putBoolean("acceptNvrCert", c.acceptNvrCert)
            .putBoolean("localNames", c.localNames)
            .putString("localHosts", c.localHosts)
            .putString("localDomains", c.localDomains)
            .putString("services", encodeServices(c.services))
            .apply()
    }

    private fun encodeServices(list: List<HomeService>): String = JSONArray().apply {
        for (s in list) put(JSONObject().put("id", s.id).put("kind", s.kind.name).put("name", s.name).put("host", s.host).put("port", s.port))
    }.toString()

    private fun decodeServices(json: String?): List<HomeService> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val kind = ServiceKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return@mapNotNull null
                HomeService(o.getString("id"), kind, o.getString("name"), o.getString("host"), o.getInt("port"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(key: String, default: E): E =
        sp.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
