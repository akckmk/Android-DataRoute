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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionCard("UniFi Protect NVR") {
            OutlinedTextField(
                value = draft.nvrHost,
                onValueChange = { draft = draft.copy(nvrHost = it) },
                label = { Text("NVR / console IP address") },
                placeholder = { Text("192.168.1.10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                "Accept the NVR's self-signed certificate",
                "Only in the built-in viewer and only for this address.",
                draft.acceptNvrCert,
            ) { draft = draft.copy(acceptNvrCert = it) }
        }

        SectionCard("What counts as local (sent over Wi-Fi)") {
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
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Internet traffic") {
            RadioGroup(
                listOf(
                    Triple(InternetMode.AUTO, "Automatic failover", "Use Wi-Fi while it has working internet, switch to cellular when it doesn't."),
                    Triple(InternetMode.ALWAYS_CELLULAR, "Always cellular", "Send all internet traffic over mobile data, even if Wi-Fi works."),
                ),
                draft.internetMode,
            ) { draft = draft.copy(internetMode = it) }
        }

        SectionCard("Local traffic handling") {
            RadioGroup(
                listOf(
                    Triple(LocalMode.TUNNEL, "Relay through DataRoute (reliable)", "Works no matter which network Android prefers. Camera video passes through this app."),
                    Triple(LocalMode.NATIVE, "Leave to Android (faster)", "Local traffic bypasses the tunnel. Only works while Android keeps Wi-Fi as its default network."),
                ),
                draft.localMode,
            ) { draft = draft.copy(localMode = it) }
        }

        SectionCard("DNS") {
            SwitchRow("Use the carrier's DNS", "Looked up from the cellular connection.", draft.useCarrierDns) {
                draft = draft.copy(useCarrierDns = it)
            }
            if (!draft.useCarrierDns) {
                OutlinedTextField(
                    value = draft.customDns,
                    onValueChange = { draft = draft.copy(customDns = it) },
                    label = { Text("DNS servers") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard("Apps that bypass the tunnel") {
            Text(
                if (draft.bypassApps.isEmpty()) "None. Every app is routed. Add apps that refuse to work behind a VPN (banking apps, for example)."
                else "${draft.bypassApps.size} app(s) bypass DataRoute and use Android's default network.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { picking = true }) { Text("Choose apps") }
        }

        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = { vm.save(draft) },
            enabled = errors.isEmpty() && draft != saved,
            modifier = Modifier.fillMaxWidth(),
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
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
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
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
        title = { Text("Apps that bypass the tunnel") },
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
