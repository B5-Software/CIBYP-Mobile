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
        devices.value = devices.value.filterNot { it.id == device.id } + device
        vault.save(devices.value)
        publishDevices()
    }
    fun forget(device: Device) {
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
        status.value = "Connecting…"; error.value = ""
        val backend = client(device)
        if (password.isNotEmpty()) { backend.login(password, code); save(device.copy(token = backend.token)) }
        val snapshot = backend.snapshot()
        if (active.value?.id != device.id) appearance.value = JSONObject()
        active.value?.id?.takeIf { it != device.id }?.let { clients.remove(it)?.close() }
        active.value = devices.value.find { it.id == device.id } ?: device
        applySnapshot(snapshot)
        loadAppearance()
        update.value = backend.rpc("ipc:invoke", JSONArray().put("updates:status")) as? JSONObject ?: JSONObject()
        subscribe(backend)
        status.value = "Connected"
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
            if (active.value?.id != backend.device.id) return@launch
            if (event.optString("type") == "reset") { applySnapshot(backend.snapshot()); loadAppearance() }
            if (event.optString("channel") == "updates:state") update.value = event.optJSONObject("payload") ?: JSONObject()
            if (event.optString("channel") == "agent:session-event" && event.optJSONObject("payload")?.optString("type") !in setOf("stream-chunk", "stream-start")) queueRefresh()
            if (event.optString("channel") in setOf("settings:changed", "theme:apply", "theme:changed")) loadAppearance()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = failure.message.orEmpty() }
        } }, onClosed = { expired -> scope.launch {
            if (active.value?.id != backend.device.id) return@launch
            if (expired) { error.value = "Session expired; sign in again"; disconnect() }
            else {
                status.value = "Reconnecting…"
                reconnect?.cancel()
                reconnect = scope.launch {
                    repeat(6) { attempt ->
                        delay((500L shl attempt).coerceAtMost(10000))
                        if (runCatching { applySnapshot(backend.snapshot()); loadAppearance(); subscribe(backend) }.isSuccess) { status.value = "Connected"; return@launch }
                    }
                    error.value = "Connection lost; reconnect from Devices"
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
        val key = selected.value
        val nextSessions = (backend.rpc("listSessions") as? JSONArray)?.objects().orEmpty().filter { it.optString("profile") != "settings-assistant" }
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
    suspend fun select(key: String) { selected.value = key; refreshNow() }
    suspend fun newSession(mode: String) { val session = rpc("createSession", JSONArray().put(JSONObject().put("mode", mode))) as JSONObject; select(session.getString("key")) }
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
    fun send(text: String, attachments: JSONArray = JSONArray(), key: String = selected.value, device: Device? = active.value, onRejected: () -> Unit = {}) { scope.launch {
        runCatching {
            if (selected.value.isEmpty()) newSession("chat")
            if (text.trim() == "/update") rpc("ipc:invoke", JSONArray().put("updates:start"))
            else if (text.trim().startsWith("/compact") && attachments.length() == 0) {
                val result = rpcFor(device ?: error("Choose a device first"), "agentAction", JSONArray().put(key).put("compactNow").put(JSONArray().put(text.trim().removePrefix("/compact").trim()))) as? JSONObject
                val compact = result?.optJSONObject("result")
                check(compact?.optBoolean("ok") == true) { compact?.optString("message") ?: "Compaction failed" }
            } else {
                val result = rpcFor(device ?: error("Choose a device first"), "sendMessage", JSONArray().put(key).put(text).put(attachments)) as? JSONObject
                check(result?.optBoolean("ok") == true) { result?.optString("error") ?: "Message was not accepted" }
            }
            refreshNow()
        }.onFailure { onRejected(); error.value = it.message.orEmpty() }
    } }
    suspend fun loadUsage() { usage.value = rpc("getSubscriptionUsage", JSONArray().put(selected.value)) as? JSONObject ?: JSONObject() }
    fun disconnect() {
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
