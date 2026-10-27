package com.dataroute.app

import android.net.Network
import com.dataroute.core.Ip
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TestResult(val name: String, val ok: Boolean?, val detail: String)

/**
 * Active checks that tell you which leg is broken. Tests that name a network are bound to it directly;
 * the "through tunnel" tests use ordinary sockets, exactly like any other app, so they prove the tunnel works.
 */
object Diagnostics {
    private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
    private val NVR_PORTS = listOf(443, 80, 7441)

    suspend fun run(cfg: Config, snap: NetSnapshot, tunnelUp: Boolean): List<TestResult> = withContext(Dispatchers.IO) {
        val out = ArrayList<TestResult>()
        val nvr = cfg.nvrHost.trim().takeIf { Ip.parse(it) != null }

        if (nvr == null) {
            out += TestResult("NVR reachable", null, "Set the NVR address in Settings first")
        } else {
            out += tcp("NVR $nvr via Wi-Fi (direct)", snap.wifi.network, nvr)
            if (tunnelUp) out += tcp("NVR $nvr through tunnel", null, nvr)
        }
        out += http("Internet via cellular (direct)", snap.cellular.network, snap.cellular.connected)
        out += http("Internet via Wi-Fi (direct)", snap.wifi.network, snap.wifi.connected)
        if (tunnelUp) out += http("Internet through tunnel", null, true)
        out
    }

    /** Tries the usual NVR ports; success on any of them (a refused port still proves L3 reachability). */
    private fun tcp(name: String, network: Network?, host: String): TestResult {
        if (network == null && name.contains("direct")) return TestResult(name, false, "Wi-Fi is not connected")
        var lastError = "no answer"
        for (port in NVR_PORTS) {
            val t0 = System.nanoTime()
            val s = Socket()
            try {
                network?.bindSocket(s)
                s.connect(InetSocketAddress(InetAddress.getByName(host), port), 2500)
                return TestResult(name, true, "port $port open, ${(System.nanoTime() - t0) / 1_000_000} ms")
            } catch (e: java.net.ConnectException) {
                // "refused" means the host answered: reachable even though that port is closed
                if (e.message?.contains("refused", ignoreCase = true) == true) {
                    return TestResult(name, true, "host answered (port $port closed)")
                }
                lastError = e.message ?: "connect failed"
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                runCatching { s.close() }
            }
        }
        return TestResult(name, false, lastError)
    }

    private fun http(name: String, network: Network?, available: Boolean): TestResult {
        if (!available) return TestResult(name, false, "network not connected")
        val t0 = System.nanoTime()
        return try {
            val url = URL(PROBE_URL)
            val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = false
            val code = try { conn.responseCode } finally { conn.disconnect() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (code == 204) TestResult(name, true, "$ms ms") else TestResult(name, false, "HTTP $code (captive portal or filtered?)")
        } catch (e: Exception) {
            TestResult(name, false, e.message ?: e.javaClass.simpleName)
        }
    }
}
