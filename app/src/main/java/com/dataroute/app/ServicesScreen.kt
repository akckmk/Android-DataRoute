package com.dataroute.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ServicesScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val cfg by vm.config.collectAsStateWithLifecycle()
    val statuses by vm.serviceStatus.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<ServiceKind?>(null) }
    val services = vm.allServices(cfg)

    LaunchedEffect(services.map { it.id }) { vm.checkServices() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Home services", "Cameras, smart home, media and storage, reachable over Wi-Fi even while the internet is down.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = ServiceKind.HOME_ASSISTANT }, shape = RoundedCornerShape(50)) {
                Icon(painterResource(R.drawable.ic_add), null, modifier = Modifier.size(18.dp))
                Text("Add service", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = { vm.checkServices() }, enabled = !checking && services.isNotEmpty(), shape = RoundedCornerShape(50)) {
                Text(if (checking) "Checking…" else "Check all")
            }
        }

        if (services.isEmpty()) {
            Bubble {
                BubbleTitle("Add your first service")
                Text("Pick what you run at home. You can change the address and port next.", style = MaterialTheme.typography.bodyMedium, color = DR.Sub)
                ServiceKind.entries.forEach { k -> KindRow(k, selected = false) { adding = k } }
            }
        }

        services.forEach { svc ->
            ServiceCard(
                svc, statuses[svc.id], vm.appInstalled(svc.kind.appPackage),
                onOpen = { ctx.startActivity(WebViewerActivity.intent(ctx, svc.url(), svc.name)) },
                onApp = { launchApp(ctx, svc.kind.appPackage, svc.kind.label) },
                onDelete = if (svc.id == NVR_ID) null else ({ vm.removeService(svc.id) }),
            )
        }

        Bubble(color = DR.GrapeSoft) {
            BubbleTitle("Names work too")
            Text(
                "Addresses like homeassistant.local, nas.lan or plex are looked up over your Wi-Fi, so they keep working when the internet is down. Add your own under Settings → Local names.",
                style = MaterialTheme.typography.bodyMedium, color = DR.Ink,
            )
        }
    }

    adding?.let { k -> AddServiceDialog(k, onDismiss = { adding = null }) { kind, name, host, port -> vm.addService(kind, name, host, port); adding = null } }
}

@Composable
private fun ServiceCard(
    svc: HomeService, status: TestResult?, appInstalled: Boolean,
    onOpen: () -> Unit, onApp: () -> Unit, onDelete: (() -> Unit)?,
) {
    val look = svc.kind.look()
    Bubble {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBlob(look.icon, look.tint, look.bg, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(svc.name, style = MaterialTheme.typography.titleMedium, color = DR.Ink)
                Text(svc.address, style = MaterialTheme.typography.bodySmall, color = DR.Sub)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.ic_delete), "Remove ${svc.name}", tint = DR.Sub) }
            }
        }
        when (status?.ok) {
            true -> Pill(status.detail, DR.MintInk, DR.MintSoft, R.drawable.ic_ok)
            false -> {
                Pill("Not reachable", DR.CoralInk, DR.CoralSoft, R.drawable.ic_fail)
                Text(status.detail, style = MaterialTheme.typography.bodySmall, color = DR.Sub)
            }
            null -> Pill(status?.detail ?: "Not checked yet", DR.Sub, DR.GrapeSoft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen, shape = RoundedCornerShape(50)) { Text("Open") }
            if (appInstalled) OutlinedButton(onClick = onApp, shape = RoundedCornerShape(50)) { Text("${svc.kind.label} app") }
        }
    }
}

@Composable
private fun KindRow(k: ServiceKind, selected: Boolean, onClick: () -> Unit) {
    val look = k.look()
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) DR.GrapeSoft else DR.Card, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBlob(look.icon, look.tint, look.bg, 38.dp)
        Column {
            Text(k.label, style = MaterialTheme.typography.titleSmall, color = DR.Ink)
            Text(k.blurb, style = MaterialTheme.typography.bodySmall, color = DR.Sub)
        }
    }
}

@Composable
private fun AddServiceDialog(initial: ServiceKind, onDismiss: () -> Unit, onSave: (ServiceKind, String, String, Int) -> Unit) {
    var kind by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(initial.label) }
    var nameTouched by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(initial.port.toString()) }
    val portN = port.toIntOrNull()
    val valid = name.isNotBlank() && HomeService.validHost(host) && portN != null && portN in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DR.Bg,
        title = { Text("Add a home service", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServiceKind.entries.forEach { k ->
                    KindRow(k, selected = k == kind) {
                        kind = k
                        port = k.port.toString()
                        if (!nameTouched) name = k.label
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it; nameTouched = true },
                    label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = { Text("Address") }, placeholder = { Text("192.168.1.20 or nas.local") },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = host.isNotEmpty() && !HomeService.validHost(host),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("${kind.label} usually uses ${kind.port}") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(kind, name, host, portN!!) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
