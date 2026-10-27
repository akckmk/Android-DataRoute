package com.dataroute.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(vm: MainViewModel, onToggle: () -> Unit, onOpenServices: () -> Unit) {
    val ctx = LocalContext.current
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val state by vm.vpnState.collectAsStateWithLifecycle()
    val error by vm.vpnError.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()

    val running = state == VpnState.RUNNING
    val internetViaWifi = cfg.internetMode == InternetMode.AUTO && snap.wifi.validated
    val services = vm.allServices(cfg)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("DataRoute", "Home devices on Wi-Fi. Everything else on cellular.")

        Hero(state, internetViaWifi, error, onToggle)

        val hints = buildList {
            if (!snap.cellular.connected) add("Cellular isn't connected. Turn on mobile data, otherwise internet traffic has nowhere to go.")
            if (running && !snap.wifi.connected) add("Wi-Fi isn't connected, so home devices can't be reached. Join your home Wi-Fi.")
            if (cfg.nvrHost.isBlank() && cfg.services.isEmpty()) add("Add your NVR or other home services so DataRoute can check them for you.")
            if (snap.wifi.connected && snap.wifi.validated && cfg.internetMode == InternetMode.AUTO) {
                add("Wi-Fi currently has working internet, so internet traffic stays on Wi-Fi. It switches to cellular automatically if Wi-Fi's ISP goes down.")
            }
        }
        hints.forEach { h ->
            Bubble(color = DR.SunnySoft) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.ic_info), null, tint = DR.SunnyInk, modifier = Modifier.size(22.dp))
                    Text(h, style = MaterialTheme.typography.bodyMedium, color = DR.Ink)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NetBubble(Modifier.weight(1f), "Wi-Fi", "Home network", R.drawable.ic_wifi, DR.SkyInk, DR.SkySoft, snap.wifi)
            NetBubble(Modifier.weight(1f), "Cellular", "Mobile data", R.drawable.ic_cell, DR.TangerineInk, DR.TangerineSoft, snap.cellular)
        }

        if (running) {
            Bubble {
                BubbleTitle("Traffic since start")
                TrafficRow("Home devices · Wi-Fi", DR.Sky, traffic.wifiDown, traffic.wifiUp)
                TrafficRow("Internet · cellular", DR.Tangerine, traffic.cellDown, traffic.cellUp)
                Text(
                    "${traffic.activeTcp + traffic.activeUdp} active connections · ${traffic.tcpOpened} opened · ${traffic.tcpFailed} failed",
                    style = MaterialTheme.typography.bodySmall, color = DR.Sub,
                )
            }
        }

        Bubble {
            BubbleTitle("Your home services")
            if (services.isEmpty()) {
                Text("Plex, Jellyfin, Home Assistant, your NAS and cameras: add them once and open them here.", style = MaterialTheme.typography.bodyMedium, color = DR.Sub)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    services.take(6).forEach { val l = it.kind.look(); IconBlob(l.icon, l.tint, l.bg, 40.dp, it.name) }
                    Text("${services.size} saved", style = MaterialTheme.typography.labelLarge, color = DR.Sub)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenServices, shape = RoundedCornerShape(50)) { Text("See services") }
                OutlinedButton(onClick = { launchProtect(ctx) }, shape = RoundedCornerShape(50)) { Text("Open UniFi Protect") }
            }
            if (cfg.nvrHost.isNotBlank()) {
                OutlinedButton(
                    onClick = { ctx.startActivity(WebViewerActivity.intent(ctx, "https://${cfg.nvrHost.trim()}", "UniFi Protect")) },
                    shape = RoundedCornerShape(50),
                ) { Text("Open NVR here") }
            }
        }

        Bubble {
            BubbleTitle("Connection tests")
            Text("Checks your home devices over Wi-Fi and the internet over cellular, the way any app would.", style = MaterialTheme.typography.bodySmall, color = DR.Sub)
            Button(onClick = { vm.runTests() }, enabled = !testing, shape = RoundedCornerShape(50)) { Text(if (testing) "Testing…" else "Run tests") }
            tests.forEach { r -> ResultRow(r) }
        }
    }
}

@Composable
private fun Hero(state: VpnState, internetViaWifi: Boolean, error: String?, onToggle: () -> Unit) {
    val running = state == VpnState.RUNNING
    val ink = if (running) Color.White else DR.Ink
    Box(Modifier.fillMaxWidth().background(if (running) DR.heroOn else DR.heroOff, RoundedCornerShape(32.dp)).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBlob(R.drawable.ic_shield, if (running) DR.Grape else DR.GrapeInk, Color.White, 52.dp)
                Text(
                    when (state) { VpnState.RUNNING -> "Routing is ON"; VpnState.STARTING -> "Starting…"; VpnState.STOPPED -> "Routing is OFF" },
                    style = MaterialTheme.typography.headlineMedium, color = ink,
                )
            }
            if (running) {
                RouteLine(R.drawable.ic_home, "Home devices  →  Wi-Fi")
                RouteLine(R.drawable.ic_globe, "Internet  →  ${if (internetViaWifi) "Wi-Fi" else "cellular"}")
            } else {
                Text(
                    "Turn on to keep cameras, Plex, Home Assistant and your NAS on Wi-Fi while streaming, messages and everything else use cellular.",
                    style = MaterialTheme.typography.bodyMedium, color = DR.Sub,
                )
            }
            if (error != null) Pill(error, DR.CoralInk, DR.CoralSoft, R.drawable.ic_fail)
            Button(
                onClick = onToggle,
                enabled = state != VpnState.STARTING,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color.White else DR.Grape,
                    contentColor = if (running) DR.GrapeInk else Color.White,
                ),
            ) {
                Icon(painterResource(R.drawable.ic_power), null, modifier = Modifier.size(20.dp))
                Text(
                    if (state == VpnState.STOPPED) "Start routing" else "Stop routing",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RouteLine(icon: Int, text: String) {
    Row(
        Modifier.background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun NetBubble(modifier: Modifier, title: String, subtitle: String, icon: Int, ink: Color, soft: Color, info: NetInfo) {
    Bubble(modifier) {
        IconBlob(icon, ink, soft, 44.dp)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = DR.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DR.Sub)
        }
        when {
            !info.connected -> Pill("Off", DR.Sub, DR.GrapeSoft)
            info.validated -> Pill("Internet OK", DR.MintInk, DR.MintSoft, R.drawable.ic_ok)
            else -> Pill("No internet", DR.CoralInk, DR.CoralSoft, R.drawable.ic_fail)
        }
        if (info.connected) {
            Text(
                listOfNotNull(info.address ?: info.address6, info.gateway?.let { "Router $it" }).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall, color = DR.Sub,
            )
        }
    }
}

@Composable
private fun TrafficRow(label: String, dot: Color, down: Long, up: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(dot, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DR.Ink, modifier = Modifier.weight(1f))
        Text("↓ ${formatBytes(down)}  ↑ ${formatBytes(up)}", style = MaterialTheme.typography.labelLarge, color = DR.Ink)
    }
}

@Composable
fun ResultRow(r: TestResult) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        when (r.ok) {
            true -> IconBlob(R.drawable.ic_ok, DR.MintInk, DR.MintSoft, 30.dp, "Passed")
            false -> IconBlob(R.drawable.ic_fail, DR.CoralInk, DR.CoralSoft, 30.dp, "Failed")
            null -> IconBlob(R.drawable.ic_info, DR.Sub, DR.GrapeSoft, 30.dp, "Skipped")
        }
        Column {
            Text(r.name, style = MaterialTheme.typography.bodyMedium, color = DR.Ink)
            Text(r.detail, style = MaterialTheme.typography.bodySmall, color = DR.Sub)
        }
    }
}

fun launchProtect(ctx: Context) = launchApp(ctx, ServiceKind.PROTECT.appPackage, "UniFi Protect")

fun launchApp(ctx: Context, pkg: String?, label: String) {
    val i = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
    if (i != null) ctx.startActivity(i) else Toast.makeText(ctx, "$label app isn't installed", Toast.LENGTH_SHORT).show()
}
