/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
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
        pingInterval(45, TimeUnit.SECONDS)
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
        call.timeout().timeout(if (body.optString("method") in setOf("sendMessage", "inject")) 600 else 120, TimeUnit.SECONDS)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) { if (continuation.isActive) continuation.resumeWithException(failure) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            val raw = it.body?.string().orEmpty()
                            val json = runCatching { JSONObject(raw) }.getOrNull()
                            if (!it.isSuccessful) throw BackendFailure(it.code, json?.optString("error") ?: "HTTP ${it.code}")
                            check(json != null) { "The backend returned an invalid response" }
                            check(!json.has("error")) { json.getString("error") }; json
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
            })
        }
    }
    suspend fun login(password: String, code: String): String {
        token = post("/api/login", JSONObject().put("password", password).put("code", code)).getString("token")
        return token
    }
    suspend fun rpc(method: String, args: JSONArray = JSONArray()): Any? {
        // Each logical request owns its ID; callers do not replay a mutation with a new ID.
        val body = JSONObject().put("id", "$clientId:${serial.incrementAndGet()}").put("method", method).put("args", args)
        // The shared server deduplicates the same logical ID. Retry transport
        // failures once, never credentials or application errors.
        val value = try { post("/api/rpc", body) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { delay(500); post("/api/rpc", body) }
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

class BackendFailure(val code: Int, message: String) : Exception(message)
