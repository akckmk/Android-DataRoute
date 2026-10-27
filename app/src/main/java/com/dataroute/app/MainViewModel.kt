package com.dataroute.app

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

const val NVR_ID = "nvr"

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

    private val _serviceStatus = MutableStateFlow<Map<String, TestResult>>(emptyMap())
    val serviceStatus: StateFlow<Map<String, TestResult>> = _serviceStatus
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    init {
        NetworkMonitor.start(app)
    }

    /** The user's services, led by the NVR from Settings when it isn't already listed. */
    fun allServices(cfg: Config): List<HomeService> {
        val nvr = cfg.nvrHost.trim()
        val builtIn = if (nvr.isNotEmpty() && cfg.services.none { it.host == nvr && it.kind == ServiceKind.PROTECT })
            listOf(HomeService(NVR_ID, ServiceKind.PROTECT, "UniFi Protect", nvr, ServiceKind.PROTECT.port)) else emptyList()
        return builtIn + cfg.services
    }

    fun addService(kind: ServiceKind, name: String, host: String, port: Int) {
        val c = _config.value
        save(c.copy(services = c.services + HomeService(UUID.randomUUID().toString(), kind, name.trim(), host.trim(), port)))
        checkServices()
    }

    fun removeService(id: String) {
        val c = _config.value
        save(c.copy(services = c.services.filterNot { it.id == id }))
        _serviceStatus.value = _serviceStatus.value - id
    }

    fun checkServices() {
        if (_checking.value) return
        val list = allServices(_config.value)
        if (list.isEmpty()) return
        viewModelScope.launch {
            _checking.value = true
            _serviceStatus.value = list.associate { it.id to TestResult(it.name, null, "Checking…") }
            val tunnelUp = vpnState.value == VpnState.RUNNING
            val results = list.map { svc -> async { svc.id to Diagnostics.probe(svc, snapshot.value, tunnelUp) } }.awaitAll()
            _serviceStatus.value = results.toMap()
            _checking.value = false
        }
    }

    fun appInstalled(pkg: String?): Boolean =
        pkg != null && getApplication<Application>().packageManager.getLaunchIntentForPackage(pkg) != null

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
