package com.dataroute.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: one tap to start/stop routing from the notification shade. */
class RouteTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch { VpnStatus.state.collect { refresh(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        when {
            VpnStatus.state.value != VpnState.STOPPED -> RouteVpnService.stop(this)
            // VPN consent has to be given once inside the app; after that the tile can start it directly.
            VpnService.prepare(this) != null -> openApp()
            else -> try {
                RouteVpnService.start(this)
            } catch (e: Exception) {
                openApp() // e.g. background-start restrictions on some OEM builds
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh(state: VpnState) {
        val tile = qsTile ?: return
        tile.state = if (state == VpnState.STOPPED) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = "DataRoute"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (state) {
                VpnState.RUNNING -> "Routing"
                VpnState.STARTING -> "Starting…"
                VpnState.STOPPED -> "Off"
            }
        }
        tile.updateTile()
    }
}
