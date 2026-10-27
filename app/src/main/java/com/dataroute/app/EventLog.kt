package com.dataroute.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory ring buffer of recent routing events, shown on the Log tab. */
object EventLog {
    private const val MAX = 300
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun add(msg: String) {
        val line = "${fmt.get()!!.format(Date())}  $msg"
        _lines.value = (_lines.value + line).takeLast(MAX)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }
}
