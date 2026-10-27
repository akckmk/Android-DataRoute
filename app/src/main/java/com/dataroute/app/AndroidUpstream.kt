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

    override fun openTcp(path: Path, dstIp: Int, dstPort: Int): Socket {
        val network = networkFor(path)
        val sock = Socket()
        try {
            sock.reuseAddress // forces the OS socket to exist so it can be protected / bound
            if (!vpn.protect(sock)) throw IOException("protect() failed")
            network.bindSocket(sock)
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(InetAddress.getByAddress(Ip.toBytes(dstIp)), dstPort), CONNECT_TIMEOUT_MS)
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    override fun openUdp(path: Path, dstIp: Int, dstPort: Int): DatagramSocket {
        val network = networkFor(path)
        val sock = DatagramSocket(null as SocketAddress?)
        try {
            if (!vpn.protect(sock)) throw IOException("protect() failed")
            network.bindSocket(sock)
            sock.connect(InetAddress.getByAddress(Ip.toBytes(dstIp)), dstPort)
            return sock
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8000
    }
}
