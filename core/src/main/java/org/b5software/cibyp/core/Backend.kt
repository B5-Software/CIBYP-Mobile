/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class Backend(val device: Device, socksPort: Int? = null) {
    private val onion = java.net.URI(device.url).host.endsWith(".onion")
    private val client = OkHttpClient.Builder().apply {
        require(!onion || socksPort != null) { "Tor must be connected before opening an onion device" }
        if (onion) proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort!!)))
        connectTimeout(60, TimeUnit.SECONDS); readTimeout(0, TimeUnit.MILLISECONDS)
        followRedirects(false); followSslRedirects(false)
    }.build()
    @Volatile var token: String = device.token
    @Volatile private var socket: WebSocket? = null
    @Volatile private var sequence: Long = 0
    private val clientId = UUID.randomUUID().toString()
    private val serial = AtomicInteger()

    private suspend fun post(route: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(device.url + route).header("X-CIBYP-Client", "1")
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        if (body.optString("method") !in setOf("sendMessage", "inject")) call.timeout().timeout(120, TimeUnit.SECONDS)
        call.execute().use { response ->
            val value = JSONObject(response.body?.string() ?: "{}")
            check(response.isSuccessful) { value.optString("error", "HTTP ${response.code}") }
            check(!value.has("error")) { value.getString("error") }; value
        }
    }
    suspend fun login(password: String, code: String): String {
        token = post("/api/login", JSONObject().put("password", password).put("code", code)).getString("token")
        return token
    }
    suspend fun rpc(method: String, args: JSONArray = JSONArray()): Any? {
        // Each logical request owns its ID; callers do not replay a mutation with a new ID.
        val value = post("/api/rpc", JSONObject().put("id", "$clientId:${serial.incrementAndGet()}").put("method", method).put("args", args))
        return value.opt("result").takeUnless { it == JSONObject.NULL }
    }
    suspend fun snapshot(): JSONObject {
        val value = rpc("snapshot") as JSONObject
        sequence = value.optLong("sequence"); return value
    }
    fun subscribe(onEvent: (JSONObject) -> Unit, onClosed: (Boolean) -> Unit) {
        val previous = socket; socket = null; previous?.cancel()
        val address = device.url.replaceFirst("http", "ws") + "/api/events?after=$sequence"
        socket = client.newWebSocket(Request.Builder().url(address).header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                val value = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (value.optString("type") == "reset") sequence = value.optLong("sequence", 0)
                else if (value.has("sequence")) {
                    if (value.getLong("sequence") <= sequence) return
                    sequence = value.getLong("sequence")
                }
                onEvent(value)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (socket === webSocket) onClosed(response?.code == 401) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (socket === webSocket) onClosed(code == 1008) }
        })
    }
    fun close() { val previous = socket; socket = null; previous?.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
