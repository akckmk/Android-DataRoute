package com.dataroute.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DataRouteTheme {
                Surface(Modifier.fillMaxSize(), color = DR.Bg) { Root(vm) }
            }
        }
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state by vm.vpnState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) RouteVpnService.start(ctx)
        else Toast.makeText(ctx, "VPN permission is required to route traffic", Toast.LENGTH_LONG).show()
    }
    fun prepareAndStart() {
        val consent = VpnService.prepare(ctx)
        if (consent != null) vpnConsent.launch(consent) else RouteVpnService.start(ctx)
    }
    // The foreground-service notification works without this permission, it is just hidden, so continue either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { prepareAndStart() }

    val onToggle: () -> Unit = {
        if (state == VpnState.STOPPED) {
            if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(ctx)) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                prepareAndStart()
            }
        } else {
            RouteVpnService.stop(ctx)
        }
    }

    Scaffold(
        containerColor = DR.Bg,
        bottomBar = {
            NavigationBar(containerColor = DR.Card, tonalElevation = 0.dp) {
                listOf(
                    "Home" to R.drawable.ic_home,
                    "Services" to R.drawable.ic_apps,
                    "Settings" to R.drawable.ic_tune,
                    "Log" to R.drawable.ic_list,
                ).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(painterResource(icon), null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DR.GrapeInk, selectedTextColor = DR.GrapeInk,
                            indicatorColor = DR.GrapeSoft, unselectedIconColor = DR.Sub, unselectedTextColor = DR.Sub,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(vm, onToggle, onOpenServices = { tab = 1 })
                1 -> ServicesScreen(vm)
                2 -> SettingsScreen(vm)
                else -> LogScreen(vm)
            }
        }
    }
}

private fun hasNotificationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
