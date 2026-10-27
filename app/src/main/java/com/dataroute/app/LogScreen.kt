package com.dataroute.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LogScreen(vm: MainViewModel) {
    val lines by vm.log.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ScreenTitle("Log", "Every connection and the network it took, newest first.") }
            OutlinedButton(onClick = { EventLog.clear() }, shape = RoundedCornerShape(50)) { Text("Clear") }
        }
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(28.dp), color = DR.Card, shadowElevation = 1.dp) {
            if (lines.isEmpty()) {
                Text("Nothing yet. Start routing and open an app.", style = MaterialTheme.typography.bodyMedium, color = DR.Sub, modifier = Modifier.padding(20.dp))
            } else {
                LazyColumn(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    itemsIndexed(lines.asReversed()) { _, line -> LogLine(line) }
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val color = when {
        "failed" in line -> DR.CoralInk
        "via WIFI" in line || "WIFI_DNS" in line || "MDNS" in line || "HOSTS" in line -> DR.SkyInk
        "via CELLULAR" in line -> DR.TangerineInk
        else -> DR.Sub
    }
    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = color, modifier = Modifier.padding(vertical = 3.dp))
}

