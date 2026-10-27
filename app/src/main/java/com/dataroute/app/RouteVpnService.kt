package com.dataroute.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.dataroute.core.Cidr
import com.dataroute.core.ForwardEngine
import com.dataroute.core.Ip
import com.dataroute.core.LocalDns
import com.dataroute.core.LocalNets
import com.dataroute.core.PacketSink
import com.dataroute.core.RoutePolicy
import com.dataroute.core.Router
import com.dataroute.core.UpstreamDnsTransport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The split-routing tunnel. Every app's IPv4 traffic is captured, then each connection is re-originated over
 * either the Wi-Fi network (local/NVR traffic) or the cellular network (internet) by [ForwardEngine].
 */
class RouteVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.dataroute.app.START"
        const val ACTION_STOP = "com.dataroute.app.STOP"
        const val ACTION_RELOAD = "com.dataroute.app.RELOAD"

        private const val CHANNEL = "dataroute"
        private const val NOTIF_ID = 1
        private const val TUN_ADDRESS = "198.18.77.2"
        private const val MTU = 1500
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun reload(ctx: Context) = send(ctx, ACTION_RELOAD)
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RouteVpnService::class.java).setAction(ACTION_STOP))
        }

        private fun send(ctx: Context, action: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RouteVpnService::class.java).setAction(action))
        }
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var tun: ParcelFileDescriptor? = null
    private var engine: ForwardEngine? = null
    private var scope: CoroutineScope? = null
    private var config = Config()

    @Volatile private var policy = RoutePolicy(emptyList())
    @Volatile private var dnsServers: List<Int> = emptyList()
    @Volatile private var localDnsSettings = LocalDns.Settings()

    /** What the tunnel was built from; if the network drifts from this we rebuild it. */
    private var builtSubnet: Cidr? = null
    private var builtCarrierDns: List<Int> = emptyList()
    private var reloadPending = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            synchronized(lock) { stopTunnelLocked() }
            VpnStatus.setState(VpnState.STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground()
        // START, RELOAD, or a null intent when the system restarts us / always-on VPN: all mean "(re)build".
        startTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        synchronized(lock) { stopTunnelLocked() }
        VpnStatus.setState(VpnState.STOPPED)
        EventLog.add("VPN permission revoked (another VPN may have started)")
        super.onRevoke()
    }

    override fun onDestroy() {
        synchronized(lock) { stopTunnelLocked() }
        VpnStatus.setState(VpnState.STOPPED)
        super.onDestroy()
    }

    // ---- tunnel lifecycle ----

    private fun startTunnel() {
        synchronized(lock) {
            stopTunnelLocked()
            VpnStatus.setError(null)
            VpnStatus.setState(VpnState.STARTING)
            NetworkMonitor.start(applicationContext)

            // prepare() is what makes this app Android's active VPN app (when the user has consented before).
            // The UI calls it too, but the tile, a settings reload or a system restart may start us directly.
            if (VpnService.prepare(this) != null) return fail("Open DataRoute and tap Start routing to allow the VPN connection")

            val cfg = Prefs(this).load()
            config = cfg
            cfg.errors().firstOrNull()?.let { return fail("Settings problem: $it") }

            val snap = NetworkMonitor.snapshot.value
            if (!snap.cellular.connected) EventLog.add("Warning: no cellular network yet (is mobile data on?)")
            if (!snap.wifi.connected) EventLog.add("Note: Wi-Fi is not connected")

            val excluded = ArrayList(RoutePolicy.NEVER_TUNNEL)
            val local = cfg.localNets(snap.wifi.subnet)
            if (cfg.localMode == LocalMode.NATIVE) excluded += local
            dnsServers = pickDns(cfg, snap, excluded)
            builtSubnet = snap.wifi.subnet
            builtCarrierDns = snap.cellular.dns
            policy = makePolicy(cfg, snap)
            localDnsSettings = makeLocalDnsSettings(cfg, snap)

            val builder = Builder()
                .setSession("DataRoute")
                .setMtu(MTU)
                .setBlocking(true)
                .addAddress(TUN_ADDRESS, 32)
                .allowBypass()
            for (r in Cidr.complement(excluded)) builder.addRoute(Ip.format(r.network), r.prefix)
            for (d in dnsServers) builder.addDnsServer(Ip.format(d))
            for (pkg in cfg.bypassApps) {
                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) { /* app was uninstalled */ }
            }
            underlyingFor(snap)?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            // lets "nas" resolve as "nas.<home domain>" the way it does on the home network
            if (cfg.localNames) for (d in snap.wifi.domains) runCatching { builder.addSearchDomain(d) }

            val pfd: ParcelFileDescriptor? = try {
                builder.establish()
            } catch (e: Exception) {
                return fail("Could not create VPN: ${e.message}")
            }
            if (pfd == null) return fail("VPN permission was not granted")
            tun = pfd

            val out = FileOutputStream(pfd.fileDescriptor)
            val sink = PacketSink { pkt ->
                try { synchronized(out) { out.write(pkt) } } catch (_: IOException) { /* tunnel is closing */ }
            }
            val upstream = AndroidUpstream(this)
            val localDns = if (cfg.localNames) LocalDns({ localDnsSettings }, UpstreamDnsTransport(upstream)) else null
            val eng = ForwardEngine(sink, upstream, Router { policy.pathFor(it) }, EventLog::add, localDns)
            engine = eng

            thread(name = "dr-tun", isDaemon = true) {
                val input = FileInputStream(pfd.fileDescriptor)
                val buf = ByteArray(32 * 1024)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) eng.onPacket(buf, n)
                    }
                } catch (_: IOException) { /* closed */ }
            }

            NetworkMonitor.holdCellular(true)
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = s
            s.launch { NetworkMonitor.snapshot.collect { onNetworkChanged(it) } }
            s.launch {
                var tick = 0
                while (isActive) {
                    publishStats(eng)
                    if (tick++ % 5 == 0) notifyStatus()
                    delay(1000)
                }
            }

            // Android takes a moment to start sending apps into a new VPN; only say "ON" once it really is.
            val viaWifi = policy.internetViaWifi
            s.launch {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                var ready = false
                for (i in 0 until 50) {
                    if (cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) { ready = true; break }
                    delay(100)
                }
                if (!ready) EventLog.add("Note: Android hasn't reported the VPN as active yet")
                VpnStatus.setState(VpnState.RUNNING)
                EventLog.add(
                    "Tunnel up: local ${local.size} prefix(es) → Wi-Fi, internet → " +
                        "${if (viaWifi) "Wi-Fi" else "cellular"}, mode ${cfg.localMode}, " +
                        "DNS ${dnsServers.joinToString { Ip.format(it) }}",
                )
            }
        }
    }

    private fun stopTunnelLocked() {
        scope?.cancel(); scope = null
        main.removeCallbacksAndMessages(null)
        reloadPending = false
        NetworkMonitor.holdCellular(false)
        engine?.stop(); engine = null
        runCatching { tun?.close() }; tun = null
    }

    private fun fail(msg: String) {
        EventLog.add(msg)
        VpnStatus.setError(msg)
        VpnStatus.setState(VpnState.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- policy / network tracking ----

    private fun makePolicy(cfg: Config, snap: NetSnapshot): RoutePolicy {
        val local = cfg.localNets(snap.wifi.subnet)
        // A carrier DNS server living in 10/8 must still be reached over cellular, not mistaken for LAN.
        val forced = dnsServers.filter { ip -> local.any { it.contains(ip) } }.toSet()
        return RoutePolicy(local, forced, internetViaWifi = cfg.internetMode == InternetMode.AUTO && snap.wifi.validated)
    }

    private fun makeLocalDnsSettings(cfg: Config, snap: NetSnapshot) = LocalDns.Settings(
        hosts = LocalDns.parseHosts(cfg.localHosts).first,
        localSuffixes = (LocalDns.DEFAULT_SUFFIXES + LocalDns.parseSuffixes(cfg.localDomains) + snap.wifi.domains).distinct(),
        wifiDns = snap.wifi.dns,
    )

    private fun pickDns(cfg: Config, snap: NetSnapshot, bypassingTunnel: List<Cidr>): List<Int> {
        val wanted = if (cfg.useCarrierDns) snap.cellular.dns else LocalNets.parseDns(cfg.customDns).first
        // A DNS server inside an excluded range would be queried over the default network, not the tunnel.
        val usable = wanted.filter { ip -> bypassingTunnel.none { it.contains(ip) } }
        return usable.take(4).ifEmpty { FALLBACK_DNS.mapNotNull(Ip::parse) }
    }

    private fun underlyingFor(snap: NetSnapshot) =
        (if (policy.internetViaWifi) snap.wifi.network else snap.cellular.network) ?: snap.cellular.network ?: snap.wifi.network

    private fun onNetworkChanged(snap: NetSnapshot) {
        val cfg = config
        policy = makePolicy(cfg, snap)
        localDnsSettings = makeLocalDnsSettings(cfg, snap)
        underlyingFor(snap)?.let { runCatching { setUnderlyingNetworks(arrayOf(it)) } }

        val subnetDrift = cfg.localMode == LocalMode.NATIVE && snap.wifi.subnet != builtSubnet
        val dnsDrift = cfg.useCarrierDns && snap.cellular.dns.isNotEmpty() && snap.cellular.dns != builtCarrierDns
        if ((subnetDrift || dnsDrift) && !reloadPending) {
            reloadPending = true
            EventLog.add("Network changed, rebuilding tunnel")
            main.postDelayed({ reloadPending = false; startTunnel() }, 1500)
        }
    }

    private fun publishStats(eng: ForwardEngine) {
        val s = eng.stats
        VpnStatus.setTraffic(
            TrafficStats(
                s.wifiUp.get(), s.wifiDown.get(), s.cellUp.get(), s.cellDown.get(),
                s.activeTcp.get(), s.activeUdp.get(), s.tcpOpened.get(), s.tcpFailed.get(),
            ),
        )
    }

    // ---- notification ----

    private fun enterForeground() {
        val nm = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "DataRoute status", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun notifyStatus() {
        val t = VpnStatus.traffic.value
        val text = "Local → Wi-Fi · Internet → ${if (policy.internetViaWifi) "Wi-Fi" else "cellular"}" +
            " · cellular ↓${formatBytes(t.cellDown)} ↑${formatBytes(t.cellUp)}"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RouteVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle("DataRoute is routing your traffic")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_route), "Stop", stop).build())
            .build()
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
