/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.b5software.cibyp.core.*
import org.json.*

class RemoteApp : Application() {
    lateinit var remote: RemoteRepository
    override fun onCreate() { super.onCreate(); remote = RemoteRepository(this) }
}

class RemoteRepository(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vault = Vault(app)
    private val prefs = app.getSharedPreferences("preferences", 0)
    val tor = TorManager(app)
    val devices = MutableStateFlow(runCatching { vault.load() }.getOrElse { emptyList() })
    val error = MutableStateFlow("")
    val status = MutableStateFlow("")
    val active = MutableStateFlow<Device?>(null)
    val sessions = MutableStateFlow<List<JSONObject>>(emptyList())
    val selected = MutableStateFlow("")
    val details = MutableStateFlow(JSONObject())
    val loadingSession = MutableStateFlow(false)
    val drafts = MutableStateFlow(runCatching {
        val raw = JSONObject(vault.loadText("drafts-v1") ?: "{}")
        raw.keys().asSequence().associateWith { ConversationDraft.from(raw.getJSONObject(it)) }
    }.getOrElse { emptyMap() })
    val sending = MutableStateFlow<Map<String, String>>(emptyMap())
    private var draftSave: Job? = null
    private val draftSaveLock = Mutex()
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong()
    val todos = MutableStateFlow<List<JSONObject>>(emptyList())
    val usage = MutableStateFlow(JSONObject())
    val update = MutableStateFlow(JSONObject())
    val appearance = MutableStateFlow(JSONObject())
    private val deviceThemes = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val appearanceSerial = java.util.concurrent.atomic.AtomicLong()
    val trustedWatches = MutableStateFlow(prefs.getStringSet("trusted-watches", emptySet())!!.toSet())
    private val clients = java.util.concurrent.ConcurrentHashMap<String, Backend>()
    private val themeObservers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val connectionLock = Mutex()
    private var refresh: Job? = null
    private var reconnect: Job? = null
    var bridges: String
        get() = prefs.getString("bridges", "")!!
        set(value) { prefs.edit().putString("bridges", value).apply() }
    fun trusted(node: String) = node in trustedWatches.value
    fun trust(node: String, allow: Boolean) {
        trustedWatches.value = if (allow) trustedWatches.value + node else trustedWatches.value - node
        prefs.edit().putStringSet("trusted-watches", trustedWatches.value).apply()
    }
    fun save(device: Device) {
        devices.value.find { it.id == device.id }?.takeIf { it.url != device.url }?.let { clients.remove(device.id)?.close() }
        devices.value = devices.value.filterNot { it.id == device.id } + device
        vault.save(devices.value)
        publishDevices()
    }
    fun forget(device: Device) {
        drafts.value = drafts.value.filterKeys { !it.startsWith(device.id + ":") }; persistDrafts()
        themeObservers.remove(device.id)
        clients.remove(device.id)?.close()
        devices.value = devices.value.filterNot { it.id == device.id }; vault.save(devices.value)
        deviceThemes.remove(device.id)
        if (active.value?.id == device.id) disconnect()
        publishDevices()
    }
    suspend fun client(device: Device): Backend = connectionLock.withLock {
        clients[device.id]?.let { return@withLock it }
        val socks = if (java.net.URI(device.url).host.endsWith(".onion")) tor.start(bridges) else null
        Backend(device, socks).also { clients[device.id] = it }
    }
    suspend fun connect(device: Device, password: String = "", code: String = "") {
        val generation = connectionGeneration.incrementAndGet()
        reconnect?.cancel(); refresh?.cancel()
        status.value = "connecting"; error.value = ""
        val backend = client(device)
        if (password.isNotEmpty()) { backend.login(password, code); save(device.copy(token = backend.token)) }
        val snapshot = backend.snapshot()
        if (connectionGeneration.get() != generation) throw CancellationException("Connection replaced")
        if (active.value?.id != device.id) { appearance.value = JSONObject(); details.value = JSONObject(); selected.value = "" }
        active.value?.id?.takeIf { it != device.id }?.let { clients.remove(it)?.close() }
        active.value = devices.value.find { it.id == device.id } ?: device
        applySnapshot(snapshot)
        loadAppearance()
        val nextUpdate = backend.rpc("ipc:invoke", JSONArray().put("updates:status")) as? JSONObject ?: JSONObject()
        if (connectionGeneration.get() != generation) throw CancellationException("Connection replaced")
        update.value = nextUpdate
        subscribe(backend)
        status.value = "connected"
        RemoteWidget.update(app)
    }
    private fun applySnapshot(snapshot: JSONObject) {
        sessions.value = snapshot.array("sessions").objects().filter { it.optString("profile") != "settings-assistant" }
        if (sessions.value.none { it.optString("key") == selected.value }) selected.value = sessions.value.firstOrNull()?.optString("key") ?: ""
        queueRefresh()
    }
    private fun subscribe(backend: Backend) {
        themeObservers.remove(backend.device.id)
        backend.subscribe(onEvent = { event -> scope.launch {
            try {
            if (active.value?.id != backend.device.id || clients[backend.device.id] !== backend) return@launch
            if (event.optString("type") == "reset") { applySnapshot(backend.snapshot()); loadAppearance() }
            if (event.optString("channel") == "updates:state") update.value = event.optJSONObject("payload") ?: JSONObject()
            if (event.optString("channel") == "agent:session-event" && event.optJSONObject("payload")?.optString("type") !in setOf("stream-chunk", "stream-start")) queueRefresh()
            if (event.optString("channel") in setOf("settings:changed", "theme:apply", "theme:changed")) loadAppearance()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = failure.message.orEmpty() }
        } }, onClosed = { expired -> scope.launch {
            if (active.value?.id != backend.device.id || clients[backend.device.id] !== backend) return@launch
            if (expired) { error.value = "Session expired; sign in again"; disconnect() }
            else {
                status.value = "reconnecting"
                reconnect?.cancel()
                val generation = connectionGeneration.get()
                reconnect = scope.launch {
                    var attempt = 0
                    while (isActive && connectionGeneration.get() == generation && active.value?.id == backend.device.id) {
                        delay((1000L shl attempt.coerceAtMost(5)).coerceAtMost(30000))
                        try {
                            val snapshot = backend.snapshot()
                            if (connectionGeneration.get() != generation) return@launch
                            applySnapshot(snapshot); loadAppearance(); subscribe(backend)
                            status.value = "connected"; error.value = ""; return@launch
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: BackendFailure) { if (failure.code == 401) { error.value = "Session expired; sign in again"; disconnect(); return@launch }; attempt++ }
                        catch (_: Exception) { attempt++ }
                    }
                }
            }
        } })
    }
    private fun queueRefresh() {
        refreshDirty = true
        if (refresh?.isActive == true) return
        refresh = scope.launch {
            while (refreshDirty) {
                delay(if (active.value?.url?.contains(".onion") == true) 2500 else 750)
                refreshDirty = false
                try { refreshNow() } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error.value = failure.message.orEmpty() }
            }
        }
    }
    private var refreshDirty = false
    private val refreshLock = Mutex()
    suspend fun refreshNow() = refreshLock.withLock {
        val device = active.value ?: return@withLock
        val backend = client(device)
        val nextSessions = (backend.rpc("listSessions") as? JSONArray)?.objects().orEmpty().filter { it.optString("profile") != "settings-assistant" }
        if (active.value?.id != device.id) return@withLock
        if (nextSessions.none { it.optString("key") == selected.value }) { selected.value = nextSessions.firstOrNull()?.optString("key").orEmpty(); details.value = JSONObject() }
        val key = selected.value
        val nextDetails = if (key.isNotEmpty()) backend.rpc("getSessionDetails", JSONArray().put(key)) as? JSONObject ?: JSONObject() else JSONObject()
        val nextTodos = (backend.rpc("getTodos") as? JSONArray)?.objects().orEmpty()
        if (active.value?.id == device.id) {
            if (sessions.value.toString() != nextSessions.toString()) sessions.value = nextSessions
            if (selected.value == key && details.value.toString() != nextDetails.toString()) details.value = nextDetails
            if (todos.value.toString() != nextTodos.toString()) todos.value = nextTodos
        }
    }
    suspend fun rpcFor(deviceId: Device, method: String, args: JSONArray = JSONArray()): Any? = client(deviceId).rpc(method, args)
    suspend fun rpc(method: String, args: JSONArray = JSONArray()): Any? = client(active.value ?: error("Choose a device first")).rpc(method, args)
    fun run(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = failure.message.orEmpty() }
    }
    suspend fun select(key: String) {
        selected.value = key; details.value = JSONObject(); loadingSession.value = true
        try { refreshNow() } finally { if (selected.value == key) loadingSession.value = false }
    }
    suspend fun newSession(mode: String) { val device = active.value ?: error("Choose a device first"); val session = rpcFor(device, "createSession", JSONArray().put(JSONObject().put("mode", mode))) as JSONObject; if (active.value?.id == device.id) select(session.getString("key")) }
    suspend fun themeFor(device: Device): JSONObject {
        observeTheme(device)
        val state = client(device).rpc("getSystemTheme") as? JSONObject ?: JSONObject()
        val fallback = if (active.value?.id == device.id) appearance.value.optJSONObject("theme") else deviceThemes[device.id]
        val theme = publicRemoteTheme(state, fallback ?: JSONObject())
        if (deviceThemes[device.id]?.toString() != theme.toString()) {
            deviceThemes[device.id] = theme
            publishDevices()
        }
        return JSONObject(theme.toString())
    }
    // A watch may select another desktop than the phone. Subscribe on demand
    // to its public appearance events rather than polling complete settings.
    private suspend fun observeTheme(device: Device) {
        if (active.value?.id == device.id || active.value == null || !themeObservers.add(device.id)) return
        try {
            val backend = client(device)
            backend.subscribe(onEvent = { event ->
                if (event.optString("type") == "reset" || event.optString("channel") in setOf("settings:changed", "theme:apply", "theme:changed")) scope.launch {
                    try { themeFor(device) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Keep the last valid theme. */ }
                }
            }, onClosed = { expired ->
                themeObservers.remove(device.id)
                if (!expired) scope.launch {
                    delay(10000)
                    if (clients[device.id] === backend && active.value != null) observeTheme(device)
                }
            })
        } catch (failure: Exception) { themeObservers.remove(device.id); throw failure }
    }
    suspend fun loadAppearance() {
        val device = active.value ?: return
        val serial = appearanceSerial.incrementAndGet()
        val next = rpcFor(device, "chat:appearance") as? JSONObject ?: JSONObject()
        val state = client(device).rpc("getSystemTheme") as? JSONObject ?: JSONObject()
        val theme = publicRemoteTheme(state, next.optJSONObject("theme") ?: JSONObject())
        next.put("theme", theme)
        if (active.value?.id != device.id || appearanceSerial.get() != serial) return
        if (appearance.value.toString() != next.toString()) appearance.value = next
        if (deviceThemes[device.id]?.toString() != theme.toString()) { deviceThemes[device.id] = theme; publishDevices() }
    }
    fun draftKey(device: String, session: String) = "$device:$session"
    fun setDraft(device: String, session: String, draft: ConversationDraft) {
        val key = draftKey(device, session)
        drafts.value = if (draft.text.isEmpty() && draft.attachments.isEmpty()) drafts.value - key else drafts.value + (key to draft)
        persistDrafts()
    }
    fun persistDrafts(immediate: Boolean = false) {
        draftSave?.cancel()
        draftSave = scope.launch {
            if (!immediate) delay(500)
            draftSaveLock.withLock {
                val value = JSONObject(drafts.value.mapValues { it.value.json() }).toString()
                withContext(Dispatchers.IO) { vault.saveText("drafts-v1", value) }
            }
        }
    }
    suspend fun submit(text: String, attachments: JSONArray, key: String, device: Device) {
            if (text.trim() == "/update") rpcFor(device, "ipc:invoke", JSONArray().put("updates:start"))
            else if (text.trim().startsWith("/compact") && attachments.length() == 0) {
                val result = rpcFor(device, "agentAction", JSONArray().put(key).put("compactNow").put(JSONArray().put(text.trim().removePrefix("/compact").trim()))) as? JSONObject
                val compact = result?.optJSONObject("result")
                check(compact?.optBoolean("ok") == true) { compact?.optString("message") ?: "Compaction failed" }
            } else {
                val result = rpcFor(device, "submitMessage", JSONArray().put(key).put(text).put(attachments)) as? JSONObject
                check(result?.optBoolean("ok") == true) { result?.optString("error") ?: "Message was not accepted" }
            }
            queueRefresh()
    }
    suspend fun history(mode: String, workspace: String = "") = (rpc("listHistory", JSONArray().put(mode).put(workspace)) as? JSONArray)?.objects().orEmpty()
    suspend fun openHistory(mode: String, id: String, workspace: String = "") {
        val device = active.value ?: error("Choose a device first")
        sessions.value.find { it.optString("mode") == mode && it.optString("conversationId") == id && (workspace.isEmpty() || it.optString("workspacePath") == workspace) }?.let { select(it.getString("key")); return }
        val entry = rpcFor(device, "createSession", JSONArray().put(JSONObject().put("mode", mode).apply { if (workspace.isNotEmpty()) put("workspacePath", workspace) })) as JSONObject
        val key = entry.getString("key")
        val result = rpcFor(device, "openHistory", JSONArray().put(key).put(id)) as? JSONObject
        check(result?.optBoolean("ok") == true) { result?.optString("error") ?: "Conversation unavailable" }
        if (active.value?.id == device.id) select(key)
    }
    suspend fun loadUsage() { usage.value = rpc("getSubscriptionUsage", JSONArray().put(selected.value)) as? JSONObject ?: JSONObject() }
    fun disconnect() {
        connectionGeneration.incrementAndGet()
        reconnect?.cancel(); refresh?.cancel()
        appearanceSerial.incrementAndGet()
        val old = active.value; active.value = null
        old?.let { clients.remove(it.id)?.close() }
        status.value = ""; sessions.value = emptyList(); selected.value = ""; details.value = JSONObject()
        appearance.value = JSONObject(); update.value = JSONObject(); usage.value = JSONObject()
        RemoteWidget.update(app)
    }
    fun stop() { disconnect(); clients.values.forEach { it.close() }; clients.clear(); themeObservers.clear(); tor.stop() }
    fun publishDevices() { scope.launch { WatchBridgeService.publish(app, devices.value, deviceThemes.toMap()) } }
}
