/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.content.Context
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.b5software.cibyp.core.*
import org.json.*

class WatchBridgeService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.REQUEST || event.data.size > 48000) return
        scope.launch {
            val repo = (application as RemoteApp).remote
            val request = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return@launch
            val response = JSONObject().put("id", request.optString("id"))
            runCatching {
                check(repo.trusted(event.sourceNodeId)) { "Approve this watch in the phone app first" }
                val method = request.getString("method"); val args = request.array("args")
                WatchProtocol.validate(method, args)
                val device = repo.devices.value.find { it.id == request.getString("device") } ?: error("Unknown device")
                check(repo.active.value != null) { "Connect from the phone first" }
                var result = repo.client(device).rpc(method, args)
                if (method == "snapshot" && result is JSONObject) result = JSONObject().put("sessions", result.array("sessions")).put("boot", result.optJSONObject("boot")).put("platform", result.optString("platform"))
                // Data Layer has a bounded payload. The watch shows recent output; the phone keeps the full transcript.
                if (method == "getSessionDetails" && result is JSONObject) {
                    val recent = result.array("messages").objects().takeLast(12).map { JSONObject(it.toString()).put("content", it.optString("content").takeLast(2500)).put("reasoning", "") }
                    result.put("messages", JSONArray(recent))
                }
                response.put("result", result ?: JSONObject.NULL)
            }.onFailure { response.put("error", it.message) }
            Wearable.getMessageClient(this@WatchBridgeService).sendMessage(event.sourceNodeId, WatchProtocol.RESPONSE, response.toString().toByteArray()).await()
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        suspend fun publish(context: Context, devices: List<Device>) {
            val data = PutDataMapRequest.create(WatchProtocol.DEVICES)
            data.dataMap.putString("devices", JSONArray(devices.map { it.publicJson() }).toString())
            data.dataMap.putLong("revision", System.currentTimeMillis())
            runCatching { Wearable.getDataClient(context).putDataItem(data.asPutDataRequest().setUrgent()).await() }
        }
    }
}
