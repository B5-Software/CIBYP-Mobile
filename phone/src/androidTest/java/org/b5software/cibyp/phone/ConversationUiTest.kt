/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.b5software.cibyp.core.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
class ConversationUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: RemoteRepository
    private val desktop = Device("ui-fixture-a", "Studio desktop", "http://127.0.0.1:1")
    private fun messages(count: Int) = JSONObject().put("messages", JSONArray((0 until count).map { JSONObject().put("id", "message-$it").put("role", "assistant").put("content", "Message $it · Review the project requirements carefully.") }))
    private fun mount(count: Int = 4) {
        repo = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RemoteApp).remote
        compose.runOnIdle { repo.active.value = desktop; repo.selected.value = "session-a"; repo.details.value = messages(count); repo.drafts.value = emptyMap() }
        compose.setContent { val appearance by repo.appearance.collectAsState(); RemotePhoneTheme(appearance) {
            Scaffold(topBar = { TopAppBar(title = { Text(desktop.name) }) }) { padding ->
                Box(Modifier.padding(padding)) { Chat(repo) {} }
            }
        } }
    }
    @After fun clear() { if (::repo.isInitialized) compose.runOnIdle { repo.active.value = null; repo.details.value = JSONObject() } }
    @Test fun draftsAreIsolatedAcrossSessionsAndDevicesAndEncryptedAtRest() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("Keep my first draft")
        compose.runOnIdle { repo.selected.value = "session-b"; repo.details.value = messages(3) }
        compose.onNode(hasSetTextAction()).assertTextEquals("Message or /update", "")
        compose.onNode(hasSetTextAction()).performTextInput("Second draft")
        compose.runOnIdle { repo.selected.value = "session-a" }
        compose.onNode(hasSetTextAction()).assertTextContains("Keep my first draft")
        compose.runOnIdle { repo.active.value = desktop.copy(id = "ui-fixture-b") }
        compose.onNode(hasSetTextAction()).assertTextEquals("Message or /update", "")
        compose.runOnIdle { repo.active.value = desktop }
        compose.onNode(hasSetTextAction()).assertTextContains("Keep my first draft")
        Thread.sleep(1000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = context.getSharedPreferences("private-devices", 0).getString("drafts-v1", "")!!
        assertFalse(saved.contains("Keep my first draft"))
        assertTrue(Vault(context).loadText("drafts-v1")!!.contains("Keep my first draft"))
    }
    @Test fun incomingMessagesDoNotJumpAwayFromTheHistoryBeingRead() {
        mount(50)
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Message 0 · Review the project requirements carefully."))
        compose.runOnIdle { repo.details.value = messages(51) }
        compose.onNodeWithText("Message 0 · Review the project requirements carefully.").assertIsDisplayed()
        compose.onNodeWithText("Latest messages").assertExists()
    }
    @Test fun conversationShowsTaskStateAndAttachmentsWithTheme() {
        mount()
        compose.runOnIdle {
            repo.appearance.value = JSONObject().put("theme", JSONObject().put("resolvedMode", "light").put("accentColor", "#4f658d").put("backgroundColor", "#f8f9fd"))
            repo.details.value = JSONObject().put("session", JSONObject().put("busy", true)).put("messages", JSONArray()
                .put(JSONObject().put("id", "user").put("role", "user").put("content", "Please review the project notes.").put("attachments", JSONArray().put(JSONObject().put("name", "project-notes.md").put("type", "text/markdown").put("size", 1024))))
                .put(JSONObject().put("id", "assistant").put("role", "assistant").put("content", "I will review the notes and keep your requirements.").put("reasoningKind", "summary").put("reasoning", "Checking the requirements and project structure.")))
        }
        compose.onNodeWithText("Task running").assertExists()
        compose.onNodeWithText("project-notes.md").assertExists()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), "phone-phase2-chat.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
