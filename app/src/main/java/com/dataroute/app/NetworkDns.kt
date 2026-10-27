package com.dataroute.app

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import com.dataroute.core.Ip
import com.dataroute.core.Path
import com.dataroute.core.PublicDns
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Answers apps' ordinary DNS queries on the network that carries the internet.
 *
 * Android 10+: the platform resolver for that exact network (DnsResolver.rawQuery). It knows the carrier's DNS servers
 * whether they're IPv4 or IPv6 (many carriers are IPv6-only) and honours Private DNS. Fallback: the network's DNS
 * servers over UDP, then public resolvers. With custom DNS configured, those servers are asked over UDP instead.
 */
class NetworkDns(private val upstream: AndroidUpstream, private val customServers: List<Int>) : PublicDns {
    private val callbacks = Executors.newCachedThreadPool { r -> Thread(r, "dr-dns").apply { isDaemon = true } }

    override fun resolve(path: Path, query: ByteArray): ByteArray? {
        val network = upstream.networkOrNull(path) ?: return null
        if (customServers.isNotEmpty()) {
            return customServers.firstNotNullOfOrNull { upstream.dnsOverUdp(network, InetAddress.getByAddress(Ip.toBytes(it)), query) }
        }
        if (Build.VERSION.SDK_INT >= 29) rawQuery(network, query)?.let { return it }
        val servers = upstream.infoFor(path).dnsAll + FALLBACK.map { InetAddress.getByName(it) }
        return servers.firstNotNullOfOrNull { upstream.dnsOverUdp(network, it, query) }
    }

    private fun rawQuery(network: Network, query: ByteArray): ByteArray? {
        if (Build.VERSION.SDK_INT < 29) return null
        val result = AtomicReference<ByteArray?>()
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        try {
            DnsResolver.getInstance().rawQuery(
                network, query, DnsResolver.FLAG_EMPTY, callbacks, cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) { result.set(answer); done.countDown() }
                    override fun onError(error: DnsResolver.DnsException) { done.countDown() }
                },
            )
            if (!done.await(5, TimeUnit.SECONDS)) cancel.cancel()
        } catch (e: Exception) {
            return null
        }
        return result.get()
    }

    private companion object {
        val FALLBACK = listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")
    }
}
