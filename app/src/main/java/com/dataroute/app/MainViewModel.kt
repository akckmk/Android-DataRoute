package com.dataroute.app

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    private val _config = MutableStateFlow(prefs.load())
    val config: StateFlow<Config> = _config

    val snapshot: StateFlow<NetSnapshot> = NetworkMonitor.snapshot
    val vpnState: StateFlow<VpnState> = VpnStatus.state
    val vpnError: StateFlow<String?> = VpnStatus.error
    val traffic: StateFlow<TrafficStats> = VpnStatus.traffic
    val log: StateFlow<List<String>> = EventLog.lines

    private val _tests = MutableStateFlow<List<TestResult>>(emptyList())
    val tests: StateFlow<List<TestResult>> = _tests
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    init {
        NetworkMonitor.start(app)
    }

    /** Persists the settings and, if the tunnel is up, rebuilds it so they take effect. */
    fun save(c: Config) {
        prefs.save(c)
        _config.value = c
        if (vpnState.value != VpnState.STOPPED) RouteVpnService.reload(getApplication())
    }

    fun runTests() {
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            _tests.value = Diagnostics.run(_config.value, snapshot.value, vpnState.value == VpnState.RUNNING)
            _testing.value = false
        }
    }

    suspend fun loadApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != app.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}
