/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.wear

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
        setContent { MaterialTheme { WatchUi(client) } }
    }
    override fun onDestroy() { client.close(); super.onDestroy() }
}

@Composable private fun WatchUi(client: PhoneClient) {
    val devices by client.devices.collectAsState(); val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf("") }; var session by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var transcript by remember { mutableStateOf(JSONObject()) }
    var page by remember { mutableStateOf("devices") }; var error by remember { mutableStateOf("") }; var usage by remember { mutableStateOf(JSONObject()) }; var update by remember { mutableStateOf(JSONObject()) }; var todos by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    fun action(block: suspend () -> Unit) { scope.launch { try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message.orEmpty() } } }
    suspend fun refresh() { sessions = (client.rpc(device, "listSessions") as? JSONArray)?.objects().orEmpty(); if (session.isNotEmpty()) transcript = client.rpc(device, "getSessionDetails", JSONArray().put(session)) as? JSONObject ?: JSONObject() }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@rememberLauncherForActivityResult
        action { if (session.isEmpty()) session = (client.rpc(device, "createSession", JSONArray().put(JSONObject().put("mode", "chat"))) as JSONObject).getString("key"); client.rpc(device, "sendMessage", JSONArray().put(session).put(text)); refresh() }
    }
    LaunchedEffect(Unit) { runCatching { client.start() }.onFailure { error = it.message.orEmpty() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(device, page, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { if (device.isNotEmpty()) while (true) { try { refresh(); update = client.rpc(device, "ipc:invoke", JSONArray().put("updates:status")) as? JSONObject ?: JSONObject() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message.orEmpty() }; delay(2000) } } }
    BackHandler(page != "devices") { page = if (page == "sessions") "devices" else "sessions"; error = "" }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 38.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("CIBYP", style = MaterialTheme.typography.titleMedium) }
            if (error.isNotEmpty()) item { Text(error); Button(onClick = { error = "" }) { Text(tr("关闭", "Dismiss")) } }
            when (page) {
                "devices" -> {
                    if (devices.isEmpty()) item { Text(tr("在手机上添加电脑并绑定此手表。无需扫码。", "Add desktops and approve this watch on your phone. No camera needed.")) }
                    items(devices, key = { it.id }) { entry -> Button(onClick = { device = entry.id; action { refresh(); page = "sessions" } }) { Text(entry.name) } }
                }
                "sessions" -> {
                    item { Button(onClick = { action { session = (client.rpc(device, "createSession", JSONArray().put(JSONObject().put("mode", "chat"))) as JSONObject).getString("key"); refresh(); page = "chat" } }) { Text(tr("新对话", "New chat")) } }
                    items(sessions) { entry -> Button(onClick = { session = entry.getString("key"); action { refresh(); page = "chat" } }) { Text(entry.optString("title").ifEmpty { "New" }) } }
                    item { Button(onClick = { action { todos = (client.rpc(device, "getTodos") as? JSONArray)?.objects().orEmpty(); page = "todos" } }) { Text(tr("待办", "Todos")) } }
                    item { Button(onClick = { action { usage = client.rpc(device, "getSubscriptionUsage", JSONArray().put(session)) as? JSONObject ?: JSONObject(); page = "usage" } }) { Text(tr("用量", "Usage")) } }
                    item { Button(onClick = { action { client.rpc(device, "ipc:invoke", JSONArray().put("updates:start")) } }) { Text("/update") } }
                    if (update.optString("phase") == "ready") item { Button(onClick = { page = "install" }) { Text(tr("重启安装", "Install update")) } }
                }
                "chat" -> {
                    items(transcript.array("messages").objects().filter { it.optString("role") != "system" }) { message -> Text(message.optString("content")) }
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
                    item { Button(onClick = { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }) { Text(tr("语音消息", "Speak")) } }
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
