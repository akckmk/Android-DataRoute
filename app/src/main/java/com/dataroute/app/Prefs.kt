package com.dataroute.app

import android.content.Context
import com.dataroute.core.Cidr
import com.dataroute.core.LocalNets

enum class LocalMode { TUNNEL, NATIVE }
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
    /** Accept the NVR's self-signed certificate inside the built-in viewer (that host only). */
    val acceptNvrCert: Boolean = true,
) {
    /** Human readable problems with the current values; empty when valid. */
    fun errors(): List<String> {
        val out = ArrayList<String>()
        out += LocalNets.build(allPrivateIsLocal, null, nvrHost, extraLocal).errors
        if (!useCarrierDns) {
            val (ok, bad) = LocalNets.parseDns(customDns)
            if (bad.isNotEmpty()) out += "Not a valid DNS server: ${bad.joinToString()}"
            else if (ok.isEmpty()) out += "Enter at least one DNS server or use the carrier's"
        }
        return out
    }

    fun localNets(wifiSubnet: Cidr?): List<Cidr> =
        LocalNets.build(allPrivateIsLocal, if (autoWifiSubnet) wifiSubnet else null, nvrHost, extraLocal).nets
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
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(key: String, default: E): E =
        sp.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
