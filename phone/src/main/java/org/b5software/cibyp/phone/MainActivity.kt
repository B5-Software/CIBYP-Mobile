/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.b5software.cibyp.core.*
import org.json.*
import java.util.UUID

private fun tr(zh: String, en: String) = if (java.util.Locale.getDefault().language == "zh") zh else en
private val LocalMotionEnabled = compositionLocalOf { true }
@Composable private fun MotionProgress(modifier: Modifier) {
    if (LocalMotionEnabled.current) LinearProgressIndicator(modifier)
    else LinearProgressIndicator(progress = { 0.35f }, modifier = modifier)
}

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); enableEdgeToEdge()
        setContent {
            val repo = (application as RemoteApp).remote
            val appearance by repo.appearance.collectAsState()
            RemotePhoneTheme(appearance) { Surface { RemoteUi(repo) {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                startForegroundService(Intent(this, ConnectionService::class.java))
            } } }
        }
    }
    override fun onStop() {
        (application as RemoteApp).remote.persistDrafts(immediate = true)
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RemoteUi(repo: RemoteRepository, startService: () -> Unit) {
    val scope = rememberCoroutineScope(); val nav = rememberNavController()
    val error by repo.error.collectAsState(); val active by repo.active.collectAsState()
    val connection by repo.status.collectAsState()
    var usageOpen by remember { mutableStateOf(false) }; var installOpen by remember { mutableStateOf(false) }
    val update by repo.update.collectAsState()
    val appearance by repo.appearance.collectAsState()
    val animations = appearance.optBoolean("animations", true)
    fun action(block: suspend () -> Unit) { scope.launch { try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { repo.error.value = failure.message.orEmpty() } } }
    Scaffold(topBar = { TopAppBar(title = { Text(active?.name ?: "CIBYP", maxLines = 1) }, navigationIcon = {
        if (nav.currentBackStackEntryAsState().value?.destination?.route != "devices") IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("返回", "Back")) }
    }, actions = {
        if (active != null) { IconButton(onClick = { action { repo.loadUsage(); usageOpen = true } }) { Icon(Icons.Default.DataUsage, tr("用量", "Usage")) }; IconButton(onClick = { nav.navigate("devices") { launchSingleTop = true } }) { Icon(Icons.Default.Devices, tr("设备", "Devices")) } }
    }) }, snackbarHost = { if (error.isNotEmpty()) Snackbar(action = { TextButton(onClick = { repo.error.value = "" }) { Text(tr("关闭", "Dismiss")) } }) { Text(error) } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (connection == "reconnecting") Surface(color = MaterialTheme.colorScheme.secondaryContainer) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Default.Sync, null); Text(tr("连接中断，正在重连。草稿已保留。", "Reconnecting. Your drafts are safe."), style = MaterialTheme.typography.bodySmall) } }
            if (update.optString("phase") in setOf("downloading", "ready", "checking", "error")) {
                ListItem(headlineContent = { Text(if (update.optString("phase") == "ready") tr("新版已下载并校验", "Update downloaded and verified") else update.optString("error", update.optString("phase"))) }, trailingContent = { if (update.optString("phase") == "ready") TextButton(onClick = { installOpen = true }) { Text(tr("重启安装", "Install")) } })
            }
            CompositionLocalProvider(LocalMotionEnabled provides animations) {
            NavHost(nav, "devices", Modifier.weight(1f), enterTransition = { if (animations) fadeIn(tween(180)) else EnterTransition.None }, exitTransition = { if (animations) fadeOut(tween(180)) else ExitTransition.None }, popEnterTransition = { if (animations) fadeIn(tween(180)) else EnterTransition.None }, popExitTransition = { if (animations) fadeOut(tween(180)) else ExitTransition.None }) {
                composable("devices") { Devices(repo, startService, onConnected = { nav.navigate("sessions") }) }
                composable("sessions") { Sessions(repo, onSession = { nav.navigate("chat") }, onTodos = { nav.navigate("todos") }, onHistory = { nav.navigate("history") }) }
                composable("history") { History(repo) { nav.navigate("chat") } }
                composable("chat") { Chat(repo) { installOpen = true } }
                composable("todos") { Todos(repo) }
            }
            }
        }
    }
    if (usageOpen) AlertDialog(onDismissRequest = { usageOpen = false }, title = { Text(tr("用量", "Usage")) }, text = { val usage by repo.usage.collectAsState(); UsageContent(usage) }, confirmButton = { TextButton(onClick = { usageOpen = false }) { Text(tr("关闭", "Close")) } })
    if (installOpen) AlertDialog(onDismissRequest = { installOpen = false }, title = { Text(tr("重启电脑后台并安装？", "Restart the desktop backend to install?")) }, text = { Text(tr("当前任务须先停止。手机会暂时断开连接；安装完成后重新连接。", "Stop running tasks first. This phone will disconnect; reconnect after installation.")) }, confirmButton = { TextButton(onClick = { installOpen = false; action { val r = repo.rpc("ipc:invoke", JSONArray().put("updates:install")) as? JSONObject; check(r?.optBoolean("ok") == true) { r?.optString("error") ?: "Install failed" } } }) { Text(tr("确认安装", "Confirm install")) } }, dismissButton = { TextButton(onClick = { installOpen = false }) { Text(tr("稍后", "Later")) } })
}

@Composable private fun Devices(repo: RemoteRepository, startService: () -> Unit, onConnected: () -> Unit) {
    val devices by repo.devices.collectAsState(); val status by repo.status.collectAsState(); val tor by repo.tor.progress.collectAsState()
    val error by repo.error.collectAsState()
    val scope = rememberCoroutineScope(); val context = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf<Device?>(null) }; var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var bridge by remember { mutableStateOf(repo.bridges) }; var watches by remember { mutableStateOf<List<com.google.android.gms.wearable.Node>>(emptyList()) }
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    val trusted by repo.trustedWatches.collectAsState()
    LaunchedEffect(Unit) { watches = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrDefault(emptyList()); repo.publishDevices() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr("你的电脑，随身同行", "Your desktop, wherever you are"), style = MaterialTheme.typography.headlineMedium) }
        item { Text(tr("在电脑的 WebUI 设置中设置访问密码并启动 Tor，复制 onion 地址。连接仅使用你自己的后台。", "Set a WebUI password and start Tor on your desktop, then copy its onion address. Connect directly to your own backend."), style = MaterialTheme.typography.bodyMedium) }
        if (status.isNotEmpty()) item { Text("${when (status) { "connecting" -> tr("正在连接…", "Connecting…"); "reconnecting" -> tr("正在重连…", "Reconnecting…"); "connected" -> tr("已连接", "Connected"); else -> status }}  $tor"); if (busy) MotionProgress(Modifier.fillMaxWidth()) }
        items(devices, key = { it.id }) { device ->
            ElevatedCard(onClick = { editing = device; adding = true; name = device.name; address = device.url; password = ""; code = "" }) {
                ListItem(headlineContent = { Text(device.name) }, supportingContent = { Text(device.url, maxLines = 2) }, leadingContent = { Icon(Icons.Default.Computer, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
            }
        }
        item { FilledTonalButton(onClick = { editing = null; adding = true; name = ""; address = ""; password = ""; code = "" }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("添加电脑", "Add desktop")) } }
        item {
            OutlinedTextField(bridge, { bridge = it }, label = { Text(tr("可选网桥", "Optional bridges")) }, supportingText = { Text("obfs4 / snowflake / webtunnel / meek (meek_lite)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { bridge = TorBridges.DEFAULT_MEEK }, enabled = !busy) { Text(tr("使用内置 meek 网桥", "Use built-in meek bridge")) }
            TextButton(onClick = {
                runCatching { TorBridges.parse(bridge).joinToString("\n") { it.line } }
                    .onSuccess { repo.bridges = it; repo.stop(); repo.error.value = "" }
                    .onFailure { repo.error.value = it.message.orEmpty() }
            }, enabled = !busy) { Text(tr("保存网桥并断开连接", "Save bridges and disconnect")) }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        item { Text(tr("绑定的手表", "Watch access"), style = MaterialTheme.typography.titleMedium) }
        if (watches.isEmpty()) item { Text(tr("先在系统中配对手表，并在手表安装 CIBYP。", "Pair your watch in Android and install CIBYP on it.")) }
        items(watches) { node -> ListItem(headlineContent = { Text(node.displayName) }, supportingContent = { Text(tr("共享设备列表，命令通过此手机转发", "Shares devices; requests are forwarded by this phone")) }, trailingContent = { Switch(checked = node.id in trusted, onCheckedChange = { repo.trust(node.id, it); repo.publishDevices() }) }) }
        if (repo.active.value != null) item { TextButton(onClick = { context.stopService(Intent(context, ConnectionService::class.java)) }) { Text(tr("断开所有连接", "Disconnect all")) } }
    }
    if (adding) AlertDialog(onDismissRequest = { if (!busy) adding = false }, title = { Text(tr("连接电脑", "Connect desktop")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(tr("名称", "Name")) }, singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text(tr("地址", "Address")) }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text(tr("访问密码（已登录可留空）", "Password (optional if signed in)")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(code, { code = it }, label = { Text(tr("二次验证码", "Verification code")) }, singleLine = true)
            if (busy) { MotionProgress(Modifier.fillMaxWidth()); Text(tor) }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && address.isNotBlank(), onClick = {
        busy = true; startService()
        connectionJob = scope.launch { try {
            val normalized = normalizeAddress(address)
            val original = editing
            val device = Device(original?.id ?: UUID.randomUUID().toString(), name.trim(), normalized, if (original?.url == normalized) original.token else "")
            repo.save(device); repo.connect(device, password, code); password = ""; adding = false; onConnected()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { repo.error.value = failure.message.orEmpty(); context.stopService(Intent(context, ConnectionService::class.java)) }
        finally { busy = false } }
    }) { Text(tr("连接", "Connect")) } }, dismissButton = { Row { if (editing != null && !busy) TextButton(onClick = { repo.forget(editing!!); adding = false }) { Text(tr("删除", "Delete")) }; TextButton(onClick = { adding = false; connectionJob?.cancel(); if (busy) { repo.stop(); context.stopService(Intent(context, ConnectionService::class.java)); busy = false } }) { Text(tr("取消", "Cancel")) } } })
}

@Composable private fun Sessions(repo: RemoteRepository, onSession: () -> Unit, onTodos: () -> Unit, onHistory: () -> Unit) {
    val sessions by repo.sessions.collectAsState(); val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { repo.refreshNow() }.onFailure { repo.error.value = it.message.orEmpty() } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (mode in listOf("chat", "code", "babe")) FilledTonalButton(onClick = { scope.launch { runCatching { repo.newSession(mode); onSession() }.onFailure { repo.error.value = it.message.orEmpty() } } }) { Text(mode.replaceFirstChar { it.uppercase() }) } } }
        item { TextButton(onClick = onTodos) { Icon(Icons.AutoMirrored.Filled.List, null); Text(tr("待办事项", "Todos")) } }
        item { TextButton(onClick = onHistory) { Icon(Icons.Default.History, null); Text(tr("历史对话", "Conversation history")) } }
        items(sessions, key = { it.getString("key") }) { session -> Card(onClick = { repo.run { repo.select(session.getString("key")); onSession() } }) { ListItem(headlineContent = { Text(session.optString("title").ifEmpty { tr("新对话", "New") }) }, supportingContent = { Text("${session.optString("mode")} · ${session.optString("status")}") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, null) }) } }
    }
}

@Composable private fun History(repo: RemoteRepository, onOpened: () -> Unit) {
    val active by repo.active.collectAsState(); val sessions by repo.sessions.collectAsState()
    var mode by remember { mutableStateOf("chat") }; var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }; var refresh by remember { mutableIntStateOf(0) }
    val workspace = sessions.firstOrNull { it.optString("mode") == "code" }?.optString("workspacePath").orEmpty()
    val scope = rememberCoroutineScope()
    LaunchedEffect(active?.id, mode, workspace, refresh) {
        busy = true; failure = ""; entries = emptyList()
        try { entries = repo.history(mode, if (mode == "code") workspace else "") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message.orEmpty() }
        finally { busy = false }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(tr("历史对话", "Conversation history"), style = MaterialTheme.typography.headlineSmall) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (value in listOf("chat", "code", "babe")) FilterChip(selected = mode == value, onClick = { mode = value }, label = { Text(value.replaceFirstChar { it.uppercase() }) }) } }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text(tr("搜索标题", "Search titles")) }, singleLine = true) }
        if (busy) item { MotionProgress(Modifier.fillMaxWidth()) }
        if (failure.isNotEmpty()) item { Text(failure, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text(tr("重试", "Retry")) } }
        if (!busy && failure.isEmpty() && entries.isEmpty()) item { Text(if (mode == "code" && workspace.isEmpty()) tr("请先在电脑上打开 Code 工作区。", "Open a Code workspace on the desktop first.") else tr("还没有历史对话", "No conversation history yet")) }
        items(entries.filter { it.optString("title").contains(query, ignoreCase = true) }, key = { it.optString("id") }) { entry ->
            Card(onClick = { if (!busy) { busy = true; val targetDevice = active?.id; val targetMode = mode; scope.launch {
                try { repo.openHistory(targetMode, entry.getString("id"), if (targetMode == "code") workspace else ""); if (repo.active.value?.id == targetDevice) onOpened() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = error.message.orEmpty() }
                finally { busy = false }
            } } }) { ListItem(headlineContent = { Text(entry.optString("title").ifEmpty { tr("新对话", "New") }) }, supportingContent = { Text("${entry.optString("updatedAt").take(10)} · ${entry.optInt("messageCount")} ${tr("条消息", "messages")}") }, leadingContent = { Icon(Icons.Default.History, null) }) }
        }
    }
}

@Composable internal fun Chat(repo: RemoteRepository, onInstall: () -> Unit) {
    val details by repo.details.collectAsState(); val scope = rememberCoroutineScope()
    val appearance by repo.appearance.collectAsState()
    val selected by repo.selected.collectAsState()
    val active by repo.active.collectAsState()
    val drafts by repo.drafts.collectAsState()
    val transfers by repo.sending.collectAsState()
    val loading by repo.loadingSession.collectAsState()
    val draftKey = repo.draftKey(active?.id.orEmpty(), selected)
    val draft = drafts[draftKey] ?: ConversationDraft()
    val input = draft.text
    val attachments = draft.attachments
    val uploading = transfers.containsKey(draftKey)
    fun edit(value: ConversationDraft) { active?.let { repo.setDraft(it.id, selected, value) } }
    val sessions by repo.sessions.collectAsState()
    val babe = sessions.find { it.optString("key") == selected }?.optString("mode") == "babe"
    val context = androidx.compose.ui.platform.LocalContext.current
    var pickerTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var download by remember { mutableStateOf<JSONObject?>(null) }
    var downloadDevice by remember { mutableStateOf<Device?>(null) }
    var preview by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pickerTarget ?: return@rememberLauncherForActivityResult
        repo.run {
            for (uri in uris) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { attachmentInfo(context, uri) }
                require(file.size <= 8 * 1024 * 1024) { tr("单个附件不能超过 8 MiB", "Attachments are limited to 8 MiB per file") }
                val current = repo.drafts.value[repo.draftKey(target.first, target.second)] ?: ConversationDraft()
                if (current.attachments.size < 10 && current.attachments.none { it.uri == uri }) repo.setDraft(target.first, target.second, current.copy(attachments = current.attachments + file))
            }
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = download; val device = downloadDevice; download = null; downloadDevice = null
        if (uri != null && file != null) repo.run {
            val result = repo.rpcFor(device ?: error("Choose a device first"), "ipc:invoke", JSONArray().put("fs:readFileBase64").put(file.getString("path"))) as? JSONObject
            check(result?.optBoolean("ok") == true) { result?.optString("error") ?: tr("附件无法读取", "Attachment unavailable") }
            val bytes = android.util.Base64.decode(result!!.getString("data").substringAfter(','), android.util.Base64.DEFAULT)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write file") }
        }
    }
    val messages = details.array("messages").objects().filter { it.optString("role") != "system" }
    val scroll = androidx.compose.runtime.saveable.rememberSaveable(draftKey, saver = androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    var previousCount by remember(draftKey) { mutableIntStateOf(0) }
    val animations = LocalMotionEnabled.current
    LaunchedEffect(draftKey, messages.size) {
        val follow = previousCount == 0 || (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= previousCount - 2
        if (messages.isNotEmpty() && follow) { if (animations) scroll.animateScrollToItem(messages.lastIndex) else scroll.scrollToItem(messages.lastIndex) }
        previousCount = messages.size
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        val session = details.optJSONObject("session") ?: sessions.find { it.optString("key") == selected } ?: JSONObject()
        TaskStatus(session, details.optJSONObject("pendingInteraction"), loading)
        details.optJSONObject("compaction")?.let { CompactionStatus(it, animations) }
        LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(messages, key = { index, message -> message.optString("id").ifEmpty { "$draftKey:$index" } }, contentType = { _, _ -> "message" }) { _, message ->
                val user = message.optString("role") == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, if (user) Alignment.End else Alignment.Start), verticalAlignment = Alignment.Top) {
                    val profile = appearance.optJSONObject(if (user) "user" else if (babe) "babe" else "ai") ?: JSONObject()
                    val accent = appearance.optJSONObject("theme")?.optString("accentColor").orEmpty()
                    if (!user) ChatAvatar(profile, false, babe, accent)
                    Column(Modifier.weight(1f, fill = false).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                        if (message.optString("content").isNotEmpty() || message.optString("reasoning").isNotEmpty()) Surface(shape = MaterialTheme.shapes.large, color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) { Column(Modifier.padding(14.dp)) {
                            if (message.optString("reasoning").isNotEmpty()) { Text(if (message.optString("reasoningKind") == "summary") tr("推理摘要", "Reasoning summary") else tr("推理内容", "Reasoning"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall); Text(message.optString("reasoning"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp)) }
                            SelectionContainer { Text(message.optString("content"), style = MaterialTheme.typography.bodyMedium) }
                        } }
                        message.array("attachments").objects().forEach { file -> AttachmentCard(file.optString("name"), file.optString("type"), file.optLong("size", -1), onClick = {
                            if (file.optBoolean("isImage")) { val device = active; repo.run {
                                val result = repo.rpcFor(device ?: error("Choose a device first"), "ipc:invoke", JSONArray().put("fs:readFileBase64").put(file.getString("path"))) as? JSONObject
                                check(result?.optBoolean("ok") == true) { result?.optString("error") ?: "Attachment unavailable" }; preview = result!!.getString("data")
                            } } else { download = file; downloadDevice = active; destination.launch(file.optString("name", "attachment")) }
                        }) }
                    }
                    if (user) ChatAvatar(profile, true, babe, accent)
                }
            }
        }
        if (scroll.canScrollForward && messages.isNotEmpty()) TextButton(modifier = Modifier.align(Alignment.End), onClick = { scope.launch { if (animations) scroll.animateScrollToItem(messages.lastIndex) else scroll.scrollToItem(messages.lastIndex) } }) { Icon(Icons.Default.ArrowDownward, null); Text(tr("最新消息", "Latest messages")) }
        val pending = details.optJSONObject("pendingInteraction")
        if (pending != null && active != null) Interaction(repo, pending, active!!, selected)
        if (attachments.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.toList().forEach { file -> AttachmentCard(file.name, file.type, file.size, onClick = { repo.run {
                if (file.type.startsWith("image/")) preview = "data:${file.type};base64," + attachmentUpload(context, file).getString("data")
                else context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.type.ifEmpty { "application/octet-stream" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } }, onRemove = { if (!uploading) edit(draft.copy(attachments = attachments - file)) }) }
        }
        if (uploading) { Text(transfers[draftKey].orEmpty(), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall); MotionProgress(Modifier.fillMaxWidth()) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(enabled = !uploading && attachments.size < 10 && active != null, onClick = { pickerTarget = active!!.id to selected; picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.AttachFile, tr("添加附件", "Attach files")) }
            OutlinedTextField(input, { edit(draft.copy(text = it)) }, modifier = Modifier.weight(1f), label = { Text(tr("消息或 /update", "Message or /update")) }, maxLines = 6)
            IconButton(enabled = session.optBoolean("busy"), onClick = { val device = active; val key = selected; if (device != null) repo.run { repo.rpcFor(device, "stop", JSONArray().put(key)) } }) { Icon(Icons.Default.Stop, tr("停止", "Stop")) }
            FilledIconButton(enabled = !uploading && (input.isNotBlank() || attachments.isNotEmpty()), onClick = {
                val text = input
                if (text.trim() == "/update install" && attachments.isEmpty()) { edit(ConversationDraft()); onInstall() }
                else {
                  val targetDevice = active ?: return@FilledIconButton
                  val targetKey = selected
                  val selectedFiles = attachments.toList()
                  repo.sending.value = repo.sending.value + (draftKey to tr("正在发送…", "Sending…"))
                  repo.run {
                    try {
                        val key = if (targetKey.isEmpty()) (repo.rpcFor(targetDevice, "createSession", JSONArray().put(JSONObject().put("mode", "chat"))) as JSONObject).getString("key") else targetKey
                        val sent = JSONArray()
                        for ((index, file) in selectedFiles.withIndex()) {
                            repo.sending.value = repo.sending.value + (draftKey to "${tr("上传附件", "Uploading attachment")} ${index + 1}/${selectedFiles.size} · ${file.name}")
                            val result = repo.rpcFor(targetDevice, "uploadAttachment", JSONArray().put(key).put(attachmentUpload(context, file))) as? JSONObject ?: error("Upload failed")
                            sent.put(result)
                        }
                        repo.sending.value = repo.sending.value + (draftKey to tr("正在确认发送…", "Confirming delivery…"))
                        repo.submit(text, sent, key, targetDevice)
                        val current = repo.drafts.value[draftKey] ?: ConversationDraft()
                        repo.setDraft(targetDevice.id, targetKey, current.copy(text = if (current.text == text) "" else current.text, attachments = current.attachments - selectedFiles.toSet()))
                        if (targetKey.isEmpty() && repo.active.value?.id == targetDevice.id) repo.select(key)
                    } finally { repo.sending.value = repo.sending.value - draftKey }
                  }
                }
            }) { Icon(Icons.AutoMirrored.Filled.Send, tr("发送", "Send")) }
        }
    }
    if (preview.isNotEmpty()) {
        val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, preview) { value = imageBitmap(preview) }
        AlertDialog(onDismissRequest = { preview = "" }, text = { if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxWidth().heightIn(max = 480.dp)) }, confirmButton = { TextButton(onClick = { preview = "" }) { Text(tr("关闭", "Close")) } })
    }
}

@Composable private fun TaskStatus(session: JSONObject, pending: JSONObject?, loading: Boolean) {
    val label = when {
        loading -> tr("正在加载对话…", "Loading conversation…")
        pending != null -> tr("等待你的决定", "Waiting for your decision")
        session.optBoolean("busy") -> tr("任务运行中", "Task running")
        session.optString("lastError").isNotEmpty() && !session.isNull("lastError") -> tr("任务失败", "Task failed")
        else -> tr("就绪", "Ready")
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (pending != null) Icons.Default.PendingActions else if (session.optBoolean("busy")) Icons.Default.PlayArrow else Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        session.optString("lastError").takeIf { it.isNotEmpty() && !session.isNull("lastError") }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun Interaction(repo: RemoteRepository, pending: JSONObject, device: Device, sessionKey: String) {
    val kind = pending.optString("kind"); val payload = pending.optJSONObject("payload") ?: JSONObject()
    val questions = payload.array("questions").objects()
    val answers = remember(pending.toString()) { mutableStateListOf<String>().apply { repeat(questions.size) { add("") } } }
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("需要你的决定", "Your decision is needed"), style = MaterialTheme.typography.titleSmall)
            Text(payload.optString("question", payload.optString("description", payload.optString("message", kind))), maxLines = 5)
            if (kind == "questions") Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                questions.forEachIndexed { index, question ->
                    Text(question.optString("question", question.optString("header")))
                    question.array("options").objects().forEach { option ->
                        TextButton(onClick = { answers[index] = option.optString("label") }) { Text(option.optString("label")) }
                    }
                    OutlinedTextField(answers[index], { answers[index] = it }, label = { Text(tr("回答", "Answer")) })
                }
            }
            Row {
                TextButton(onClick = { repo.run { repo.rpcFor(device, "respond", JSONArray().put(sessionKey).put(if (kind == "tool-auth") "deny" else false)); repo.refreshNow() } }) { Text(tr("拒绝", "Deny")) }
                TextButton(onClick = { repo.run {
                    if (kind == "questions") repo.rpcFor(device, "answerQuestions", JSONArray().put(sessionKey).put(JSONArray(answers.toList())))
                    else repo.rpcFor(device, "respond", JSONArray().put(sessionKey).put(if (kind == "tool-auth") "allow-once" else true))
                    repo.refreshNow()
                } }) { Text(tr("确认", "Confirm")) }
            }
        }
    }
}

@Composable private fun Todos(repo: RemoteRepository) {
    val todos by repo.todos.collectAsState(); val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { repo.refreshNow() }.onFailure { repo.error.value = it.message.orEmpty() } }
    LazyColumn(contentPadding = PaddingValues(16.dp)) { items(todos, key = { it.get("id").toString() }) { todo ->
        ListItem(headlineContent = { Text(todo.optString("text")) }, leadingContent = { Checkbox(todo.optBoolean("done"), { repo.run { repo.rpc("toggleTodo", JSONArray().put(todo.get("id"))); repo.refreshNow() } }) })
    } }
}
