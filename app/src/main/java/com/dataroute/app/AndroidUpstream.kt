package com.dataroute.app

import android.net.Network
import android.net.VpnService
import com.dataroute.core.Ip
import com.dataroute.core.Path
import com.dataroute.core.Upstream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * Opens sockets that leave on a *specific* physical network, bypassing the VPN itself:
 * protect() takes them out of the tunnel, bindSocket() pins them to Wi-Fi or cellular.
 */
class AndroidUpstream(private val vpn: VpnService) : Upstream {

    // No cellular (e.g. mobile data off): fall back to Wi-Fi so a healthy Wi-Fi still works.
    override fun resolve(wanted: Path): Path {
        val s = NetworkMonitor.snapshot.value
        return if (wanted == Path.CELLULAR && s.cellular.network == null && s.wifi.network != null) Path.WIFI else wanted
    }

    private fun networkFor(path: Path): Network {
        val s = NetworkMonitor.snapshot.value
        return when (path) {
            Path.WIFI -> s.wifi.network ?: throw IOException("Wi-Fi is not connected")
            Path.CELLULAR -> s.cellular.network ?: throw IOException("No cellular network (is mobile data on?)")
        }
    }

    fun networkOrNull(path: Path): Network? = try { networkFor(resolve(path)) } catch (e: IOException) { null }

    fun infoFor(path: Path): NetInfo = NetworkMonitor.snapshot.value.let { if (path == Path.WIFI) it.wifi else it.cellular }

    /**
     * On an IPv6-only network (common on US carriers) an IPv4 destination may have no route; reach it through the
     * carrier's NAT64 instead (prefix + IPv4 address), as the platform's own 464XLAT would.
     */
    private fun nat64(path: Path, dstIp: Int): InetAddress? {
        val prefix = infoFor(path).nat64 ?: return null
        return InetAddress.getByAddress(prefix.toByteArray() + Ip.toBytes(dstIp))
    }

    override fun openTcp(path: Path, dstIp: Int, dstPort: Int): Socket {
        // The virtual DNS address only answers UDP/53 (inside the engine). Refuse anything else at once, e.g. the
        // DNS-over-TLS probe Android's "Automatic" Private DNS makes, so it falls back to plain DNS without a wait.
        if (dstIp == VIRTUAL_DNS) throw IOException("virtual DNS server: UDP/53 only")
        val network = networkFor(path)
        return try {
            tcp(network, InetAddress.getByAddress(Ip.toBytes(dstIp)), dstPort)
        } catch (e: IOException) {
            val v6 = nat64(path, dstIp) ?: throw e
            tcp(network, v6, dstPort)
        }
    }

    private fun tcp(network: Network, address: InetAddress, dstPort: Int): Socket {
        val sock = Socket()
        try {
            sock.reuseAddress // forces the OS socket to exist so it can be protected / bound
            if (!vpn.protect(sock)) throw IOException("protect() failed")
            network.bindSocket(sock)
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(address, dstPort), CONNECT_TIMEOUT_MS)
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    override fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket {
        if (dstIp == VIRTUAL_DNS) throw IOException("virtual DNS server: UDP/53 only")
        val network = networkFor(path)
        return try {
            udp(network, InetAddress.getByAddress(Ip.toBytes(dstIp)), dstPort)
        } catch (e: IOException) {
            val v6 = nat64(path, dstIp) ?: throw e
            udp(network, v6, dstPort)
        }
    }

    private fun udp(network: Network, address: InetAddress, dstPort: Int): DatagramSocket {
        val sock = DatagramSocket(null as SocketAddress?)
        try {
            if (!vpn.protect(sock)) throw IOException("protect() failed")
            network.bindSocket(sock)
            sock.connect(address, dstPort)
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** One DNS exchange with [server] (IPv4 or IPv6) over [network]; null on timeout or error. */
    fun dnsOverUdp(network: Network, server: InetAddress, query: ByteArray, timeoutMs: Int = 2000): ByteArray? {
        val sock = DatagramSocket(null as SocketAddress?)
        return try {
            if (!vpn.protect(sock)) return null
            network.bindSocket(sock)
            sock.soTimeout = timeoutMs
            sock.send(java.net.DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
            val buf = ByteArray(4096)
            val p = java.net.DatagramPacket(buf, buf.size)
            sock.receive(p)
            if (p.length >= 12 && buf[0] == query[0] && buf[1] == query[1]) buf.copyOf(p.length) else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { sock.close() }
        }
    }

    override fun openUdpRaw(path: Path): DatagramSocket {
        val network = networkFor(path)
        val sock = DatagramSocket(null as SocketAddress?)
        try {
            if (!vpn.protect(sock)) throw IOException("protect() failed")
            network.bindSocket(sock)
            sock.bind(InetSocketAddress(0))
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8000
        val VIRTUAL_DNS: Int = Ip.parse("198.18.77.53")!!
    }
}
