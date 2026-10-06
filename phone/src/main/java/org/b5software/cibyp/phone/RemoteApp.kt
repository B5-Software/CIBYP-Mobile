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
    val trustedWatches = MutableStateFlow(prefs.getStringSet("trusted-watches", emptySet())!!.toSet())
    private val clients = java.util.concurrent.ConcurrentHashMap<String, Backend>()
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
        clients.remove(device.id)?.close()
        devices.value = devices.value.filterNot { it.id == device.id }; vault.save(devices.value)
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
        backend.subscribe(onEvent = { event -> scope.launch {
            if (active.value?.id != backend.device.id) return@launch
            if (event.optString("type") == "reset") applySnapshot(backend.snapshot())
            if (event.optString("channel") == "updates:state") update.value = event.optJSONObject("payload") ?: JSONObject()
            if (event.optString("channel") == "agent:session-event") queueRefresh()
            if (event.optString("channel") == "settings:changed") loadAppearance()
        } }, onClosed = { expired -> scope.launch {
            if (active.value?.id != backend.device.id) return@launch
            if (expired) { error.value = "Session expired; sign in again"; disconnect() }
            else {
                status.value = "Reconnecting…"
                reconnect?.cancel()
                reconnect = scope.launch {
                    repeat(6) { attempt ->
                        delay((500L shl attempt).coerceAtMost(10000))
                        if (runCatching { applySnapshot(backend.snapshot()); subscribe(backend) }.isSuccess) { status.value = "Connected"; return@launch }
                    }
                    error.value = "Connection lost; reconnect from Devices"
                }
            }
        } })
    }
    private fun queueRefresh() {
        if (refresh?.isActive == true) return
        refresh = scope.launch { delay(160); runCatching { refreshNow() }.onFailure { error.value = it.message.orEmpty() } }
    }
    suspend fun refreshNow() {
        val device = active.value ?: return
        val backend = client(device)
        sessions.value = (backend.rpc("listSessions") as? JSONArray)?.objects().orEmpty().filter { it.optString("profile") != "settings-assistant" }
        if (selected.value.isNotEmpty()) details.value = backend.rpc("getSessionDetails", JSONArray().put(selected.value)) as? JSONObject ?: JSONObject()
        todos.value = (backend.rpc("getTodos") as? JSONArray)?.objects().orEmpty()
    }
    suspend fun rpcFor(deviceId: Device, method: String, args: JSONArray = JSONArray()): Any? = client(deviceId).rpc(method, args)
    suspend fun rpc(method: String, args: JSONArray = JSONArray()): Any? = client(active.value ?: error("Choose a device first")).rpc(method, args)
    fun run(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = failure.message.orEmpty() }
    }
    suspend fun select(key: String) { selected.value = key; refreshNow() }
    suspend fun newSession(mode: String) { val session = rpc("createSession", JSONArray().put(JSONObject().put("mode", mode))) as JSONObject; select(session.getString("key")) }
    suspend fun loadAppearance() { appearance.value = rpc("chat:appearance") as? JSONObject ?: JSONObject() }
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
        val old = active.value; active.value = null
        old?.let { clients.remove(it.id)?.close() }
        status.value = ""; sessions.value = emptyList(); selected.value = ""; details.value = JSONObject()
        appearance.value = JSONObject(); update.value = JSONObject(); usage.value = JSONObject()
        RemoteWidget.update(app)
    }
    fun stop() { disconnect(); clients.values.forEach { it.close() }; clients.clear(); tor.stop() }
    fun publishDevices() { scope.launch { WatchBridgeService.publish(app, devices.value) } }
}
