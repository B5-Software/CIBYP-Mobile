/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.wear

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.b5software.cibyp.core.Device
import org.json.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses the production watch UI; test data/transport live only in the test APK. */
class WatchUiTest {
    @get:Rule val compose = createComposeRule()
    private class Fixture : WatchConnection {
        override val devices = MutableStateFlow(listOf(Device("desktop", "Studio desktop", "https://example.com")))
        override val appearance = MutableStateFlow(JSONObject())
        fun theme(dark: Boolean) { appearance.value = JSONObject().put("theme", JSONObject().put("resolvedMode", if (dark) "dark" else "light").put("accentColor", if (dark) "#88c0d0" else "#315d7c").put("backgroundColor", if (dark) "#121c25" else "#f5f9fc")) }
        override suspend fun start() {}
        override fun selectDevice(device: String) { if (device.isNotEmpty()) theme(false) else appearance.value = JSONObject() }
        override suspend fun rpc(device: String, method: String, args: JSONArray): Any? = when (method) {
            "appearance:theme" -> appearance.value
            "listSessions" -> JSONArray().put(JSONObject().put("key", "chat").put("title", "Project review"))
            "getSessionDetails" -> JSONObject().put("messages", JSONArray().put(JSONObject().put("id", "one").put("role", "user").put("content", "Review notes.")).put(JSONObject().put("id", "two").put("role", "assistant").put("content", "Done.").put("reasoningSummary", "Checked notes.").put("attachments", JSONArray().put(JSONObject().put("name", "notes.md")))))
            else -> JSONObject()
        }
        override fun close() {}
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        File(directory, name).outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun targetThemeChangesWithoutRecreatingTheScreen() {
        val fixture = Fixture()
        compose.setContent { val appearance by fixture.appearance.collectAsState(); RemoteWatchTheme(appearance) { WatchUi(fixture) } }
        compose.onNodeWithText("Studio desktop").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Project review").fetchSemanticsNodes().isNotEmpty() }
        screenshot("wear-light-sessions.png")
        compose.onNodeWithText("Project review").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Review notes.").fetchSemanticsNodes().isNotEmpty() }
        screenshot("wear-light-chat.png")
        compose.onNode(hasScrollAction()).performScrollToIndex(2)
        screenshot("wear-light-summary.png")
        compose.runOnIdle { fixture.theme(true) }
        screenshot("wear-dark-summary.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Review notes."))
        screenshot("wear-dark-chat.png")
        compose.onNodeWithText("Review notes.").assertExists()
    }
}
