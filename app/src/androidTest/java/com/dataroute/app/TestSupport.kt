package com.dataroute.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

internal const val PKG = "com.dataroute.app"
/**
 * A stand-in NVR answering "NVR-OK". CI runs it on the host machine (tools/fake_nvr.py); the emulator reaches the
 * host at its Wi-Fi gateway address, so the traffic really crosses the Wi-Fi network like a real NVR's would.
 * (A server on the phone itself wouldn't do: the kernel delivers a device's own address via loopback, never the VPN.)
 */
internal const val FAKE_NVR_HOST = "10.0.2.2"
internal const val FAKE_NVR_PORT = 7441 // a real UniFi Protect port
internal const val SHOT_DIR = "/data/local/tmp/dataroute-screens"

internal val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Runs a command as the shell user and waits for it to finish. */
internal fun shell(cmd: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
    return FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }.also { pfd.close() }
}

/** Grants what a person would grant by tapping through the first-run prompts. */
internal fun prepareDevice() {
    shell("pm grant $PKG android.permission.POST_NOTIFICATIONS")
    shell("appops set $PKG ACTIVATE_VPN allow") // = tapping "OK" on the system VPN consent dialog
    shell("mkdir -p $SHOT_DIR")
    if (VpnStatus.state.value != VpnState.STOPPED) {
        RouteVpnService.stop(ctx)
        waitFor(10_000) { VpnStatus.state.value == VpnState.STOPPED }
    }
}

/** Saved into /data/local/tmp (shell-owned) so the CI job can pull it after the app is uninstalled. */
internal fun screenshot(name: String) {
    Thread.sleep(700) // let animations/recomposition settle
    shell("screencap -p $SHOT_DIR/$name.png")
}

internal fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return true
        Thread.sleep(100)
    }
    return cond()
}

internal fun wifiIp(): String? {
    waitFor(15_000) { NetworkMonitor.snapshot.value.wifi.address != null }
    return NetworkMonitor.snapshot.value.wifi.address
}

/** A plain HTTP GET through the normal network stack, i.e. exactly the way any other app's traffic travels. */
internal fun httpGet(url: String): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    return try {
        val code = c.responseCode
        code to (if (code in 200..299) c.inputStream.bufferedReader().readText() else "")
    } finally {
        c.disconnect()
    }
}
