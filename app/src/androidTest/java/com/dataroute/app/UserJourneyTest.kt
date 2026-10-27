package com.dataroute.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Walks through the app the way a person would, saving a screenshot at each step. */
@RunWith(AndroidJUnit4::class)
class UserJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun setUp() {
        prepareDevice()
        Prefs(ctx).save(Config())
    }

    @After fun tearDown() {
        RouteVpnService.stop(ctx)
        waitFor(10_000) { VpnStatus.state.value == VpnState.STOPPED }
        Prefs(ctx).save(Config())
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000, substring: Boolean = false) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test fun firstRunToCameras() {
        val ip = FAKE_NVR_HOST
        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. First launch
            compose.onNodeWithText("Routing is OFF").assertIsDisplayed()
            screenshot("01_home_off")

            // 2. Settings: enter the NVR address and pick "Always cellular" for internet
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("NVR / console IP address").performTextInput(ip)
            compose.onNodeWithText("Always cellular").performScrollTo().performClick()
            screenshot("02_settings")
            compose.onNodeWithText("Save").performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(Prefs(ctx).load().nvrHost == ip)

            // 3. Start routing (the VPN consent was pre-granted, as after the first real tap on "OK")
            compose.onNodeWithText("Home").performClick()
            compose.onNodeWithText("Start routing").performClick()
            waitForText("Routing is ON")
            screenshot("03_home_on")

            // 4. Add a home service (the stand-in server, as Home Assistant) and see it reachable
            compose.onNodeWithText("Services").performClick()
            compose.onNodeWithText("Add service").performClick()
            compose.onNodeWithText("Address").performTextInput(ip)
            compose.onNodeWithText("Port").performTextClearance()
            compose.onNodeWithText("Port").performTextInput("$FAKE_NVR_PORT")
            screenshot("04_add_service")
            compose.onNodeWithText("Add").performClick()
            waitForText("Reachable", 30_000, substring = true)
            screenshot("05_services")

            // 5. Connection tests, including through the live tunnel
            compose.onNodeWithText("Home").performClick()
            compose.onNodeWithText("Run tests").performScrollTo().performClick()
            waitForText("Internet through tunnel", 60_000)
            compose.onNodeWithText("Internet through tunnel").performScrollTo()
            screenshot("06_tests")

            // 6. Log of every routed connection
            compose.onNodeWithText("Log").performClick()
            screenshot("07_log")

            // 7. Stop
            compose.onNodeWithText("Home").performClick()
            compose.onNodeWithText("Stop routing").performClick()
            waitForText("Routing is OFF")
            screenshot("08_stopped")
        }
    }
}
