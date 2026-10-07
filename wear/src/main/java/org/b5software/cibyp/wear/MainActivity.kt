/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.wear

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.b5software.cibyp.core.*
import org.json.*

private fun tr(zh: String, en: String) = if (java.util.Locale.getDefault().language == "zh") zh else en

class MainActivity : ComponentActivity() {
    private lateinit var client: PhoneClient
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); client = PhoneClient(this)
        setContent { val appearance by client.appearance.collectAsState(); RemoteWatchTheme(appearance) { WatchUi(client) } }
    }
    override fun onDestroy() { client.close(); super.onDestroy() }
}

@Composable internal fun WatchUi(client: WatchConnection) {
    val devices by client.devices.collectAsState(); val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf("") }; var session by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var transcript by remember { mutableStateOf(JSONObject()) }
    var page by remember { mutableStateOf("devices") }; var error by remember { mutableStateOf("") }; var usage by remember { mutableStateOf(JSONObject()) }; var update by remember { mutableStateOf(JSONObject()) }; var todos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var actionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var historyMode by remember { mutableStateOf("chat") }
    var voiceTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scroll = androidx.compose.runtime.saveable.rememberSaveable(device, session, page, saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    val messages = remember(transcript) { transcript.array("messages").objects().filter { it.optString("role") != "system" } }
    fun action(block: suspend () -> Unit) {
        if (working) return
        actionJob = scope.launch { working = true; error = ""; try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message.orEmpty() } finally { working = false } }
    }
    suspend fun refresh(targetPage: String = page) {
        val target = device; val key = session
        when (targetPage) {
            "sessions" -> {
                val next = (client.rpc(target, "listSessions") as? JSONArray)?.objects().orEmpty()
                if (device == target && sessions.toString() != next.toString()) sessions = next
            }
            "history" -> {
                val mode = historyMode
                val workspace = if (mode == "code") sessions.firstOrNull { it.optString("mode") == "code" }?.optString("workspacePath").orEmpty() else ""
                val next = (client.rpc(target, "listHistory", JSONArray().put(mode).put(workspace)) as? JSONArray)?.objects().orEmpty()
                if (device == target && historyMode == mode) history = next
            }
            "chat" -> if (key.isNotEmpty()) {
                val next = client.rpc(target, "getSessionDetails", JSONArray().put(key)) as? JSONObject ?: JSONObject()
                if (device == target && session == key && transcript.toString() != next.toString()) transcript = next
            }
            "todos" -> {
                val next = (client.rpc(target, "getTodos") as? JSONArray)?.objects().orEmpty()
                if (device == target && todos.toString() != next.toString()) todos = next
            }
            "usage" -> {
                val next = client.rpc(target, "getSubscriptionUsage", JSONArray().put(key)) as? JSONObject ?: JSONObject()
                if (device == target && usage.toString() != next.toString()) usage = next
            }
        }
        if (targetPage in setOf("sessions", "install")) {
            val next = client.rpc(target, "ipc:invoke", JSONArray().put("updates:status")) as? JSONObject ?: JSONObject()
            if (device == target && update.toString() != next.toString()) update = next
        }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@rememberLauncherForActivityResult
        val target = voiceTarget ?: return@rememberLauncherForActivityResult
        if (device != target.first || session != target.second) return@rememberLauncherForActivityResult
        action {
            val key = if (target.second.isEmpty()) (client.rpc(target.first, "createSession", JSONArray().put(JSONObject().put("mode", "chat"))) as JSONObject).getString("key") else target.second
            val accepted = client.rpc(target.first, "submitMessage", JSONArray().put(key).put(text)) as? JSONObject
            check(accepted?.optBoolean("ok") == true) { accepted?.optString("error") ?: tr("发送未被确认", "Delivery was not confirmed") }
            if (device == target.first) { session = key; refresh("chat") }
        }
    }
    LaunchedEffect(Unit) { runCatching { client.start() }.onFailure { error = it.message.orEmpty() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(device, page, session, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        if (device.isNotEmpty() && page != "devices") while (true) {
            try { if (!working) { refreshing = true; refresh(); error = "" } } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = tr("连接暂不可用，将自动重试。", "Connection unavailable. Retrying automatically.") + "\n" + failure.message.orEmpty() } finally { refreshing = false }
            // Tor calls can take seconds. Wait after completion, never overlap
            // refreshes; the Data Layer pushes appearance changes separately.
            delay(if (page == "chat") 10000 else 30000)
        }
    } }
    BackHandler(page != "devices") { actionJob?.cancel(); voiceTarget = null; page = if (page == "sessions") "devices" else "sessions"; error = ""; if (page == "devices") client.selectDevice("") }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 38.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("CIBYP", style = MaterialTheme.typography.titleMedium) }
            if (working || refreshing) item { Text(if (working) tr("正在处理…", "Working…") else tr("同步中…", "Syncing…"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            if (error.isNotEmpty()) item { Text(error); Button(onClick = { error = "" }) { Text(tr("关闭", "Dismiss")) } }
            when (page) {
                "devices" -> {
                    if (devices.isEmpty()) item { Text(tr("在手机上添加电脑并绑定此手表。无需扫码。", "Add desktops and approve this watch on your phone. No camera needed.")) }
                    items(devices, key = { it.id }) { entry -> Button(enabled = !working, onClick = { device = entry.id; session = ""; transcript = JSONObject(); sessions = emptyList(); client.selectDevice(device); action { client.rpc(device, "appearance:theme"); refresh("sessions"); page = "sessions" } }) { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                }
                "sessions" -> {
                    item { Button(enabled = !working, onClick = { action { session = (client.rpc(device, "createSession", JSONArray().put(JSONObject().put("mode", "chat"))) as JSONObject).getString("key"); refresh("chat"); page = "chat" } }) { Text(tr("新对话", "New chat")) } }
                    items(sessions, key = { it.optString("key") }) { entry -> Button(enabled = !working, onClick = { session = entry.getString("key"); action { refresh("chat"); page = "chat" } }) { Text(entry.optString("title").ifEmpty { tr("新对话", "New") }, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                    item { Button(enabled = !working, onClick = { action { historyMode = "chat"; refresh("history"); page = "history" } }) { Text(tr("历史对话", "History")) } }
                    item { Button(onClick = { action { todos = (client.rpc(device, "getTodos") as? JSONArray)?.objects().orEmpty(); page = "todos" } }) { Text(tr("待办", "Todos")) } }
                    item { Button(onClick = { action { usage = client.rpc(device, "getSubscriptionUsage", JSONArray().put(session)) as? JSONObject ?: JSONObject(); page = "usage" } }) { Text(tr("用量", "Usage")) } }
                    item { Button(onClick = { action { client.rpc(device, "ipc:invoke", JSONArray().put("updates:start")) } }) { Text("/update") } }
                    if (update.optString("phase") == "ready") item { Button(onClick = { page = "install" }) { Text(tr("重启安装", "Install update")) } }
                }
                "history" -> {
                    for (mode in listOf("chat", "code", "babe")) item { Button(enabled = !working, onClick = { action { historyMode = mode; refresh("history") } }) { Text(if (historyMode == mode) "✓ $mode" else mode) } }
                    if (history.isEmpty()) item { Text(tr("暂无历史记录", "No history yet")) }
                    items(history, key = { it.optString("id") }) { entry -> Button(enabled = !working, onClick = { action {
                        val target = device; val mode = historyMode
                        val workspace = if (mode == "code") sessions.firstOrNull { it.optString("mode") == "code" }?.optString("workspacePath").orEmpty() else ""
                        val key = sessions.firstOrNull { it.optString("mode") == mode && it.optString("conversationId") == entry.optString("id") }?.optString("key") ?: (client.rpc(target, "createSession", JSONArray().put(JSONObject().put("mode", mode).apply { if (workspace.isNotEmpty()) put("workspacePath", workspace) })) as JSONObject).getString("key").also {
                            val result = client.rpc(target, "openHistory", JSONArray().put(it).put(entry.getString("id"))) as? JSONObject
                            check(result?.optBoolean("ok") == true) { result?.optString("error") ?: "Conversation unavailable" }
                        }
                        if (device == target) { session = key; refresh("chat"); page = "chat" }
                    } }) { Text(entry.optString("title").ifEmpty { tr("新对话", "New") }, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                }
                "chat" -> {
                    item {
                        val task = transcript.optJSONObject("session") ?: JSONObject()
                        Text(when { transcript.has("pendingInteraction") -> tr("等待你的决定", "Decision needed"); task.optBoolean("busy") -> tr("任务运行中", "Task running"); task.optString("lastError").isNotEmpty() -> tr("任务失败", "Task failed"); else -> tr("就绪", "Ready") }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (task.optString("lastError").isNotEmpty()) Text(task.optString("lastError"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (transcript.optInt("earlierMessages") > 0) item { Text(tr("更早的消息可在手机上查看", "Earlier messages are available on your phone"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(messages, key = { it.optString("id") }, contentType = { "message" }) { message -> Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (message.optString("role") == "user") tr("你", "You") else "AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(message.optString("content"), style = MaterialTheme.typography.bodyMedium)
                        if (message.optString("reasoningSummary").isNotEmpty()) {
                            Text(tr("推理摘要", "Reasoning summary"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(message.optString("reasoningSummary"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        for (file in message.array("attachments").objects()) Text("▣ " + file.optString("name"), style = MaterialTheme.typography.bodySmall)
                        if (message.optBoolean("truncated")) Text(tr("完整内容请在手机查看", "Open the phone for the full message"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    val pending = transcript.optJSONObject("pendingInteraction")
                    if (pending != null) {
                        item { Text(tr("需要你的决定，请查看手机上的完整请求。", "Decision needed. Review the complete request on your phone.")) }
                        item { val payload = pending.optJSONObject("payload") ?: JSONObject(); Text(payload.optString("description", payload.optString("command", payload.optString("message", pending.optString("kind")))).take(2000)) }
                        if (pending.optString("kind") != "questions") item { Button(onClick = { action {
                            val kind = pending.optString("kind")
                            client.rpc(device, "respond", JSONArray().put(session).put(if (kind == "tool-auth") "allow-once" else true)); refresh()
                        } }) { Text(tr("允许一次", "Allow once")) } }
                        item { Button(onClick = { action { client.rpc(device, "respond", JSONArray().put(session).put(if (pending.optString("kind") == "tool-auth") "deny" else false)); refresh() } }) { Text(tr("拒绝", "Deny")) } }
                    }
                    item { Button(enabled = !working, onClick = { voiceTarget = device to session; voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }) { Text(tr("语音消息", "Speak")) } }
                    item { Button(onClick = { action { client.rpc(device, "stop", JSONArray().put(session)); refresh() } }) { Text(tr("停止任务", "Stop task")) } }
                }
                "todos" -> items(todos) { todo -> Button(onClick = { action { client.rpc(device, "toggleTodo", JSONArray().put(todo.get("id"))); todos = (client.rpc(device, "getTodos") as? JSONArray)?.objects().orEmpty() } }) { Text((if (todo.optBoolean("done")) "✓ " else "○ ") + todo.optString("text")) } }
                "usage" -> {
                    val windows = usage.array("windows").objects()
                    if (usage.has("error")) item { Text(usage.optString("error")) }
                    items(windows) { window -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val period = when (window.optString("period")) { "5hour" -> tr("5 小时", "5 hours"); "weekly" -> tr("每周", "Weekly"); "monthly" -> tr("每月", "Monthly"); else -> window.optString("period") }
                        Text("${window.optString("label")} · $period")
                        Text("${window.optDouble("usedPercent", 0.0).toInt()}% ${tr("已使用", "used")}")
                    } }
                    val cost = usage.optJSONObject("daily") ?: usage.optJSONObject("equivalent")
                    if (cost != null) item { Text("${tr("API 等效消费", "API equivalent cost")} $${String.format(java.util.Locale.US, "%.4f", cost.optDouble("costUSD", 0.0))}") }
                    if (windows.isEmpty() && cost == null) item { Text(tr("没有额度数据", "No usage data")) }
                }
                "install" -> {
                    item { Text(tr("停止运行任务后，重启电脑后台并安装新版？", "Restart the desktop backend and install? Stop running tasks first.")) }
                    item { Button(onClick = { action { val result = client.rpc(device, "ipc:invoke", JSONArray().put("updates:install")) as? JSONObject; check(result?.optBoolean("ok") == true) { result?.optString("error").orEmpty() }; page = "devices" } }) { Text(tr("确认安装", "Confirm install")) } }
                    item { Button(onClick = { page = "sessions" }) { Text(tr("稍后", "Later")) } }
                }
            }
        }
    }
}
