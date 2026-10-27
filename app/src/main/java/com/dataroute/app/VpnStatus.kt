package com.dataroute.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VpnState { STOPPED, STARTING, RUNNING }

data class TrafficStats(
    val wifiUp: Long = 0, val wifiDown: Long = 0,
    val cellUp: Long = 0, val cellDown: Long = 0,
    val activeTcp: Int = 0, val activeUdp: Int = 0,
    val tcpOpened: Long = 0, val tcpFailed: Long = 0,
)

/** Process-wide status the service publishes and the UI observes. */
object VpnStatus {
    private val _state = MutableStateFlow(VpnState.STOPPED)
    private val _error = MutableStateFlow<String?>(null)
    private val _traffic = MutableStateFlow(TrafficStats())
    val state: StateFlow<VpnState> = _state
    val error: StateFlow<String?> = _error
    val traffic: StateFlow<TrafficStats> = _traffic

    fun setState(s: VpnState) { _state.value = s }
    fun setError(e: String?) { _error.value = e }
    fun setTraffic(t: TrafficStats) { _traffic.value = t }
}
