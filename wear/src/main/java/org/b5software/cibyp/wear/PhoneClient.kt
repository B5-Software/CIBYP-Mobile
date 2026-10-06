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

class PhoneClient(private val context: Context) : MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    val devices = MutableStateFlow<List<Device>>(emptyList())
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private var phone = ""
    suspend fun start() {
        Wearable.getMessageClient(context).addListener(this).await()
        Wearable.getDataClient(context).addListener(this).await()
        Wearable.getDataClient(context).dataItems.await().use { items -> for (item in items) receive(item) }
        phone = Wearable.getNodeClient(context).connectedNodes.await().firstOrNull { it.isNearby }?.id ?: ""
    }
    private fun receive(item: DataItem) {
        if (item.uri.path != WatchProtocol.DEVICES) return
        val value = DataMapItem.fromDataItem(item).dataMap.getString("devices") ?: "[]"
        devices.value = JSONArray(value).objects().map(Device::from)
        phone = item.uri.host.orEmpty()
    }
    override fun onDataChanged(events: DataEventBuffer) { for (event in events) if (event.type == DataEvent.TYPE_CHANGED) receive(event.dataItem) }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.RESPONSE || event.sourceNodeId != phone) return
        val value = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return
        val request = pending.remove(value.optString("id")) ?: return
        if (value.has("error")) request.completeExceptionally(IllegalStateException(value.getString("error")))
        else request.complete(value.opt("result").takeUnless { it == JSONObject.NULL })
    }
    suspend fun rpc(device: String, method: String, args: JSONArray = JSONArray()): Any? {
        check(phone.isNotEmpty()) { "Connect your phone and approve this watch first" }
        WatchProtocol.validate(method, args)
        val id = UUID.randomUUID().toString(); val deferred = CompletableDeferred<Any?>(); pending[id] = deferred
        try {
            val value = JSONObject().put("id", id).put("device", device).put("method", method).put("args", args)
            Wearable.getMessageClient(context).sendMessage(phone, WatchProtocol.REQUEST, value.toString().toByteArray()).await()
            return withTimeout(if (method == "sendMessage") 600000 else 60000) { deferred.await() }
        } finally { pending.remove(id) }
    }
    fun close() {
        Wearable.getMessageClient(context).removeListener(this); Wearable.getDataClient(context).removeListener(this)
        pending.values.forEach { it.cancel() }; pending.clear()
    }
}
