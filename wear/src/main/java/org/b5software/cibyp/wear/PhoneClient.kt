/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.wear

import android.content.Context
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.b5software.cibyp.core.*
import org.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PhoneClient(private val context: Context) : WatchConnection, MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    override val devices = MutableStateFlow<List<Device>>(emptyList())
    override val appearance = MutableStateFlow(JSONObject())
    private val themes = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var selectedDevice = ""
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var phone = ""
    override suspend fun start() {
        Wearable.getMessageClient(context).addListener(this).await()
        Wearable.getDataClient(context).addListener(this).await()
        Wearable.getDataClient(context).dataItems.await().use { items -> for (item in items) receive(item) }
        Wearable.getNodeClient(context).connectedNodes.await().firstOrNull { it.isNearby }?.id?.let { phone = it }
    }
    private fun receive(item: DataItem) {
        if (item.uri.path != WatchProtocol.DEVICES) return
        val value = DataMapItem.fromDataItem(item).dataMap.getString("devices") ?: "[]"
        val entries = JSONArray(value).objects()
        devices.value = entries.map(Device::from)
        themes.keys.retainAll(devices.value.map { it.id }.toSet())
        for (entry in entries) entry.optJSONObject("theme")?.let { themes[entry.getString("id")] = it }
        applyTheme()
        phone = item.uri.host.orEmpty()
    }
    override fun onDataChanged(events: DataEventBuffer) { for (event in events) if (event.type == DataEvent.TYPE_CHANGED) receive(event.dataItem) }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.RESPONSE || event.sourceNodeId != phone) return
        if (event.data.size > 48000) return
        scope.launch {
        val value = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return@launch
        val request = pending.remove(value.optString("id")) ?: return@launch
        if (value.has("error")) request.completeExceptionally(IllegalStateException(value.getString("error")))
        else request.complete(value.opt("result").takeUnless { it == JSONObject.NULL })
        }
    }
    override fun selectDevice(device: String) { selectedDevice = device; applyTheme() }
    private fun applyTheme() {
        val next = JSONObject().apply { themes[selectedDevice]?.takeIf { it.length() > 0 }?.let { put("theme", it) } }
        if (appearance.value.toString() != next.toString()) appearance.value = next
    }
    override suspend fun rpc(device: String, method: String, args: JSONArray): Any? {
        check(phone.isNotEmpty()) { "Connect your phone and approve this watch first" }
        WatchProtocol.validate(method, args)
        check(pending.size < 4) { "The watch is busy; try again shortly" }
        val id = UUID.randomUUID().toString(); val deferred = CompletableDeferred<Any?>(); pending[id] = deferred
        try {
            val value = JSONObject().put("id", id).put("device", device).put("method", method).put("args", args)
            Wearable.getMessageClient(context).sendMessage(phone, WatchProtocol.REQUEST, value.toString().toByteArray()).await()
            return withTimeout(if (method == "sendMessage") 600000 else 120000) { deferred.await() }.also { result ->
                if (method == "appearance:theme" && result is JSONObject) {
                    result.optJSONObject("theme")?.let { themes[device] = it }
                    if (device == selectedDevice) applyTheme()
                }
            }
        } finally { pending.remove(id) }
    }
    override fun close() {
        scope.cancel()
        Wearable.getMessageClient(context).removeListener(this); Wearable.getDataClient(context).removeListener(this)
        pending.values.forEach { it.cancel() }; pending.clear()
    }
}
