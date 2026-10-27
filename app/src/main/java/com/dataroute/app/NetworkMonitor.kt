package com.dataroute.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.dataroute.core.Cidr
import com.dataroute.core.Ip
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NetInfo(
    val network: Network? = null,
    /** The platform's verdict: true when Android's connectivity probe reached the internet. */
    val validated: Boolean = false,
    val address: String? = null,
    val subnet: Cidr? = null,
    val gateway: String? = null,
    /** IPv4 DNS servers handed out by this network. */
    val dns: List<Int> = emptyList(),
    /** Search domains handed out by this network (e.g. the UniFi gateway's domain name). */
    val domains: List<String> = emptyList(),
) {
    val connected: Boolean get() = network != null
}

data class NetSnapshot(val wifi: NetInfo = NetInfo(), val cellular: NetInfo = NetInfo())

/**
 * Tracks the Wi-Fi and cellular networks independently of which one Android currently treats as "default",
 * and can pin the cellular radio on while Wi-Fi is connected.
 */
object NetworkMonitor {
    private val _snapshot = MutableStateFlow(NetSnapshot())
    val snapshot: StateFlow<NetSnapshot> = _snapshot

    private var cm: ConnectivityManager? = null
    private var wifi: Tracker? = null
    private var cellular: Tracker? = null
    private var holdCallback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(context: Context) {
        if (cm != null) return
        val c = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm = c
        wifi = Tracker(NetworkCapabilities.TRANSPORT_WIFI).also { c.registerNetworkCallback(request(it.transport), it) }
        cellular = Tracker(NetworkCapabilities.TRANSPORT_CELLULAR).also { c.registerNetworkCallback(request(it.transport), it) }
    }

    /**
     * While held, Android keeps the mobile data connection up even though Wi-Fi is connected (it would otherwise be
     * free to tear it down), so it is ready the moment traffic needs it.
     */
    @Synchronized
    fun holdCellular(hold: Boolean) {
        val c = cm ?: return
        if (hold && holdCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {}
            try {
                c.requestNetwork(request(NetworkCapabilities.TRANSPORT_CELLULAR), cb)
                holdCallback = cb
            } catch (e: SecurityException) {
                EventLog.add("Could not hold cellular network: ${e.message}")
            } catch (e: RuntimeException) {
                EventLog.add("Could not hold cellular network: ${e.message}")
            }
        } else if (!hold) {
            holdCallback?.let { runCatching { c.unregisterNetworkCallback(it) } }
            holdCallback = null
        }
    }

    private fun request(transport: Int): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

    private fun publish() {
        _snapshot.value = NetSnapshot(
            wifi = wifi?.best() ?: NetInfo(),
            cellular = cellular?.best() ?: NetInfo(),
        )
    }

    private class Tracker(val transport: Int) : ConnectivityManager.NetworkCallback() {
        private val nets = LinkedHashMap<Network, NetInfo>()

        fun best(): NetInfo = synchronized(nets) { nets.values.maxByOrNull { if (it.validated) 1 else 0 } } ?: NetInfo()

        private fun update(n: Network, f: (NetInfo) -> NetInfo) {
            synchronized(nets) { nets[n] = f(nets[n] ?: NetInfo(network = n)) }
            publish()
        }

        override fun onAvailable(network: Network) = update(network) { it }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            update(network) { it.copy(validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            val dns = lp.dnsServers.filterIsInstance<Inet4Address>().map { Ip.fromBytes(it.address) }
            update(network) {
                it.copy(
                    address = v4?.address?.hostAddress,
                    subnet = v4?.let { a -> Cidr.of(Ip.fromBytes(a.address.address), a.prefixLength) },
                    gateway = gw,
                    dns = dns,
                    domains = lp.domains?.split(' ', ',')?.map { d -> d.trim().trim('.').lowercase() }?.filter { d -> d.isNotEmpty() } ?: emptyList(),
                )
            }
        }

        override fun onLost(network: Network) {
            synchronized(nets) { nets.remove(network) }
            publish()
        }
    }
}
