package com.dataroute.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val PROTECT_PACKAGE = "com.ubnt.unifi.protect"

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(),
    content: @Composable () -> Unit,
) {
    Card(modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun StatusScreen(vm: MainViewModel, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val state by vm.vpnState.collectAsStateWithLifecycle()
    val error by vm.vpnError.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()

    val running = state == VpnState.RUNNING
    val internetVia = if (cfg.internetMode == InternetMode.AUTO && snap.wifi.validated) "Wi-Fi" else "cellular"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DataRoute", style = MaterialTheme.typography.headlineMedium)

        SectionCard(
            title = when (state) {
                VpnState.RUNNING -> "Routing is ON"
                VpnState.STARTING -> "Starting…"
                VpnState.STOPPED -> "Routing is OFF"
            },
            colors = CardDefaults.cardColors(
                containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            if (running) {
                Text("Local network (NVR, cameras)  →  Wi-Fi")
                Text("Everything else (internet)  →  $internetVia")
            } else {
                Text("Start routing to keep your cameras reachable over Wi-Fi while streaming, messaging and everything else uses cellular when Wi-Fi has no internet.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onToggle, enabled = state != VpnState.STARTING, modifier = Modifier.fillMaxWidth()) {
                Text(if (state == VpnState.STOPPED) "Start routing" else "Stop routing")
            }
        }

        val hints = buildList {
            if (!snap.cellular.connected) add("Cellular isn't connected. Turn on mobile data, otherwise internet traffic has nowhere to go.")
            if (running && !snap.wifi.connected) add("Wi-Fi isn't connected, so the NVR can't be reached. Join your UniFi Wi-Fi.")
            if (cfg.nvrHost.isBlank()) add("Add your NVR's IP address in Settings so it can be tested.")
            if (snap.wifi.connected && snap.wifi.validated && cfg.internetMode == InternetMode.AUTO) {
                add("Wi-Fi currently has working internet, so internet traffic stays on Wi-Fi. It switches to cellular automatically if Wi-Fi's ISP goes down.")
            }
        }
        if (hints.isNotEmpty()) {
            SectionCard("Heads up") { hints.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        }

        NetCard("Wi-Fi", snap.wifi)
        NetCard("Cellular", snap.cellular)

        if (running) {
            SectionCard("Traffic since start") {
                TrafficRow("Wi-Fi (local)", traffic.wifiDown, traffic.wifiUp)
                TrafficRow("Cellular", traffic.cellDown, traffic.cellUp)
                Text(
                    "${traffic.activeTcp} TCP + ${traffic.activeUdp} UDP active · ${traffic.tcpOpened} opened · ${traffic.tcpFailed} failed",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard("Cameras") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { ctx.startActivity(Intent(ctx, NvrActivity::class.java)) },
                    enabled = cfg.nvrHost.isNotBlank(),
                ) { Text("Open NVR here") }
                OutlinedButton(onClick = { launchProtect(ctx) }) { Text("Open UniFi Protect") }
            }
            Text(
                "With routing on, the UniFi Protect app and this viewer reach the NVR over Wi-Fi even if Android has switched everything else to cellular.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Connection tests") {
            Button(onClick = { vm.runTests() }, enabled = !testing) { Text(if (testing) "Testing…" else "Run tests") }
            tests.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (r.ok) { true -> "✓"; false -> "✗"; null -> "–" },
                        color = when (r.ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.outline },
                    )
                    Column {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium)
                        Text(r.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NetCard(title: String, info: NetInfo) {
    SectionCard(title) {
        Text(
            when {
                !info.connected -> "Not connected"
                info.validated -> "Connected · internet working"
                else -> "Connected · no internet"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (info.connected) {
            Text(
                listOfNotNull(
                    info.address?.let { "IP $it" + (info.subnet?.let { s -> "  ($s)" } ?: "") },
                    info.gateway?.let { "Gateway $it" },
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TrafficRow(label: String, down: Long, up: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text("↓ ${formatBytes(down)}   ↑ ${formatBytes(up)}")
    }
}

private fun launchProtect(ctx: Context) {
    val i = ctx.packageManager.getLaunchIntentForPackage(PROTECT_PACKAGE)
    if (i != null) ctx.startActivity(i) else Toast.makeText(ctx, "UniFi Protect app isn't installed", Toast.LENGTH_SHORT).show()
}
