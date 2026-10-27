package com.dataroute.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val saved by vm.config.collectAsStateWithLifecycle()
    val state by vm.vpnState.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    var picking by remember { mutableStateOf(false) }
    val errors = draft.errors()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Settings", "The defaults suit most homes. Add your NVR and you're set.")

        Bubble {
            BubbleTitle("UniFi Protect NVR")
            OutlinedTextField(
                value = draft.nvrHost,
                onValueChange = { draft = draft.copy(nvrHost = it) },
                label = { Text("NVR / console IP address") },
                placeholder = { Text("192.168.1.10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                "Trust my home devices' certificates",
                "In the built-in viewer only, and only for local addresses (NVR, NAS, Home Assistant…).",
                draft.acceptNvrCert,
            ) { draft = draft.copy(acceptNvrCert = it) }
        }

        Bubble {
            BubbleTitle("What stays on Wi-Fi")
            SwitchRow(
                "All private ranges",
                "10.x.x.x, 172.16–31.x.x and 192.168.x.x. Covers cameras on other VLANs.",
                draft.allPrivateIsLocal,
            ) { draft = draft.copy(allPrivateIsLocal = it) }
            SwitchRow(
                "Current Wi-Fi subnet",
                "Detected automatically from the connected network.",
                draft.autoWifiSubnet,
            ) { draft = draft.copy(autoWifiSubnet = it) }
            OutlinedTextField(
                value = draft.extraLocal,
                onValueChange = { draft = draft.copy(extraLocal = it) },
                label = { Text("Extra local subnets (optional)") },
                placeholder = { Text("100.64.0.0/10, 203.0.113.5") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Bubble {
            BubbleTitle("Internet traffic")
            RadioGroup(
                listOf(
                    Triple(InternetMode.AUTO, "Automatic failover", "Use Wi-Fi while it has working internet, switch to cellular when it doesn't."),
                    Triple(InternetMode.ALWAYS_CELLULAR, "Always cellular", "Send all internet traffic over mobile data, even if Wi-Fi works."),
                ),
                draft.internetMode,
            ) { draft = draft.copy(internetMode = it) }
        }

        Bubble {
            BubbleTitle("Home traffic handling")
            RadioGroup(
                listOf(
                    Triple(LocalMode.TUNNEL, "Relay through DataRoute (reliable)", "Works no matter which network Android prefers. Video from cameras, Plex and Jellyfin passes through this app."),
                    Triple(LocalMode.NATIVE, "Leave to Android (faster)", "Home traffic skips DataRoute. Only works while Android keeps Wi-Fi as its default network."),
                ),
                draft.localMode,
            ) { draft = draft.copy(localMode = it) }
        }

        Bubble {
            BubbleTitle("Local names")
            SwitchRow(
                "Find home devices by name",
                "homeassistant.local, nas.lan and your router's names are looked up over Wi-Fi.",
                draft.localNames,
            ) { draft = draft.copy(localNames = it) }
            OutlinedTextField(
                value = draft.localHosts,
                onValueChange = { draft = draft.copy(localHosts = it) },
                label = { Text("My names (optional)") },
                placeholder = { Text("nas = 192.168.1.20\nplex = 192.168.1.30") },
                minLines = 2,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.localDomains,
                onValueChange = { draft = draft.copy(localDomains = it) },
                label = { Text("Extra home domains (optional)") },
                placeholder = { Text("myhome.net") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Bubble {
            BubbleTitle("DNS")
            SwitchRow("Use the carrier's DNS", "Looked up from the cellular connection.", draft.useCarrierDns) {
                draft = draft.copy(useCarrierDns = it)
            }
            if (!draft.useCarrierDns) {
                OutlinedTextField(
                    value = draft.customDns,
                    onValueChange = { draft = draft.copy(customDns = it) },
                    label = { Text("DNS servers") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Bubble {
            BubbleTitle("Apps that bypass DataRoute")
            Text(
                if (draft.bypassApps.isEmpty()) "None. Every app is routed. Add apps that refuse to work behind a VPN (banking apps, for example)."
                else "${draft.bypassApps.size} app(s) bypass DataRoute and use Android's default network.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { picking = true }, shape = RoundedCornerShape(50)) { Text("Choose apps") }
        }

        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { vm.save(draft) },
            enabled = errors.isEmpty() && draft != saved,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        ) { Text(if (state != VpnState.STOPPED) "Save and apply" else "Save") }
    }

    if (picking) {
        AppPickerDialog(vm, draft.bypassApps, onDismiss = { picking = false }) {
            draft = draft.copy(bypassApps = it)
            picking = false
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = DR.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DR.Sub)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = DR.Grape, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
        )
    }
}

@Composable
private fun <T> RadioGroup(options: List<Triple<T, String, String>>, selected: T, onSelect: (T) -> Unit) {
    options.forEach { (value, title, subtitle) ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = value == selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = DR.Ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = DR.Sub)
            }
        }
    }
}

@Composable
private fun AppPickerDialog(vm: MainViewModel, selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var chosen by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) { apps = vm.loadApps() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DR.Bg,
        title = { Text("Apps that bypass DataRoute", style = MaterialTheme.typography.headlineSmall) },
        text = {
            val list = apps
            if (list == null) {
                CircularProgressIndicator()
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.pkg }) { app ->
                        val on = app.pkg in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - app.pkg else chosen + app.pkg }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
