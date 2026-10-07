/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class Device(val id: String, val name: String, val url: String, val token: String = "") {
    fun publicJson() = JSONObject().put("id", id).put("name", name).put("url", url)
    fun json() = publicJson().put("token", token)
    companion object {
        fun from(o: JSONObject) = Device(o.getString("id"), o.getString("name"), normalizeAddress(o.getString("url")), o.optString("token"))
    }
}

fun normalizeAddress(raw: String): String {
    val uri = URI(raw.trim())
    val host = uri.host?.lowercase() ?: error("Invalid device address")
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "Use an origin without credentials or a path" }
    val onion = Regex("^[a-z2-7]{56}\\.onion$").matches(host)
    val parts = host.split('.')
    val ipv4 = parts.mapNotNull { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } }
    val lan = host == "localhost" || (parts.size == 4 && ipv4.size == 4 && (ipv4[0] == 127 || ipv4[0] == 10 || (ipv4[0] == 192 && ipv4[1] == 168) || (ipv4[0] == 172 && ipv4[1] in 16..31)))
    require(uri.scheme == "https" || (uri.scheme == "http" && (onion || lan))) { "Use a Tor onion, HTTPS, or a private LAN address" }
    require(!host.endsWith(".onion") || onion) { "Only v3 onion addresses are supported" }
    return "${uri.scheme}://${if (host.contains(':')) "[$host]" else host}${if (uri.port > 0) ":${uri.port}" else ""}"
}

fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()

object WatchProtocol {
    const val REQUEST = "/cibyp/request"
    const val RESPONSE = "/cibyp/response"
    const val DEVICES = "/cibyp/devices"
    val methods = setOf("appearance:theme", "snapshot", "listSessions", "listHistory", "openHistory", "createSession", "getSessionDetails", "getStats", "getSubscriptionUsage", "sendMessage", "submitMessage", "stop", "getTodos", "toggleTodo", "respond", "answerQuestions")
    fun validate(method: String, args: JSONArray) {
        require(method in methods || (method == "ipc:invoke" && args.optString(0) in setOf("updates:start", "updates:status", "updates:install"))) { "Unsupported watch operation" }
        require(args.toString().length < 48000) { "Watch request too large" }
    }
}
