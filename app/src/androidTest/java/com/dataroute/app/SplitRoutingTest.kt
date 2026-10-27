package com.dataroute.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core promise, checked on a real Android system: with the tunnel up, the NVR (a local address) is reached
 * over Wi-Fi while internet traffic is carried by the cellular network, at the same time.
 */
@RunWith(AndroidJUnit4::class)
class SplitRoutingTest {
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        prepareDevice()
    }

    @After fun tearDown() {
        RouteVpnService.stop(ctx)
        waitFor(10_000) { VpnStatus.state.value == VpnState.STOPPED }
        scenario?.close()
        Prefs(ctx).save(Config())
    }

    @Test fun nvrViaWifiAndInternetViaCellularSimultaneously() {
        val ip = wifiIp()
        assumeTrue("emulator has no Wi-Fi network", ip != null)
        assumeTrue("emulator has no cellular network", waitFor(15_000) { NetworkMonitor.snapshot.value.cellular.connected })

        Prefs(ctx).save(Config(nvrHost = FAKE_NVR_HOST, internetMode = InternetMode.ALWAYS_CELLULAR))
        scenario = ActivityScenario.launch(MainActivity::class.java) // foreground app may start the service
        RouteVpnService.start(ctx)
        assertTrue("tunnel came up", waitFor(20_000) { VpnStatus.state.value == VpnState.RUNNING })

        // Local: the fake NVR on the Wi-Fi address
        val (nvrCode, nvrBody) = httpGet("http://$FAKE_NVR_HOST:$FAKE_NVR_PORT/")
        assertEquals(200, nvrCode)
        assertEquals("NVR-OK", nvrBody)

        // Internet: Google's connectivity check
        val (netCode, _) = httpGet("http://connectivitycheck.gstatic.com/generate_204")
        assertEquals(204, netCode)

        // And the engine's own accounting confirms which network each one actually used
        assertTrue("NVR bytes went over Wi-Fi", waitFor(5_000) { VpnStatus.traffic.value.wifiDown > 0 })
        assertTrue("internet bytes went over cellular", waitFor(5_000) { VpnStatus.traffic.value.cellDown > 0 })
        assertTrue(EventLog.lines.value.any { it.contains("$FAKE_NVR_HOST:$FAKE_NVR_PORT via WIFI connected") })
        assertTrue(EventLog.lines.value.any { it.contains(":80 via CELLULAR connected") })
    }

    /** A name from the "My names" list resolves inside the tunnel and is reached over Wi-Fi. */
    @Test fun localNameResolvesThroughTheTunnel() {
        val ip = wifiIp()
        assumeTrue("emulator has no Wi-Fi network", ip != null)
        Prefs(ctx).save(Config(nvrHost = FAKE_NVR_HOST, localHosts = "fakenvr = $FAKE_NVR_HOST"))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        RouteVpnService.start(ctx)
        assertTrue("tunnel came up", waitFor(20_000) { VpnStatus.state.value == VpnState.RUNNING })

        val (code, body) = httpGet("http://fakenvr:$FAKE_NVR_PORT/")
        assertEquals(200, code)
        assertEquals("NVR-OK", body)
        assertTrue("name answered by DataRoute", EventLog.lines.value.any { it.contains("DNS fakenvr answered via HOSTS") })
        assertTrue("bytes went over Wi-Fi", waitFor(5_000) { VpnStatus.traffic.value.wifiDown > 0 })
    }

    @Test fun stopsCleanly() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        RouteVpnService.start(ctx)
        assertTrue(waitFor(20_000) { VpnStatus.state.value == VpnState.RUNNING })
        RouteVpnService.stop(ctx)
        assertTrue(waitFor(10_000) { VpnStatus.state.value == VpnState.STOPPED })
    }
}
