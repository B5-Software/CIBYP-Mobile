/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import org.json.JSONArray
import org.json.JSONObject

/** Keep the small watch viewport independent from the complete phone transcript. */
object WatchPayload {
    const val MAX_MESSAGES = 8
    const val MAX_TEXT = 800
    const val MAX_SESSIONS = 40
    fun text(value: String, limit: Int = MAX_TEXT): String =
        if (value.codePointCount(0, value.length) <= limit) value
        else value.substring(0, value.offsetByCodePoints(0, limit)) + "…"

    fun sessions(value: JSONArray) = JSONArray(value.objects().filter { it.optString("profile") != "settings-assistant" }.takeLast(MAX_SESSIONS).map {
        JSONObject().put("key", it.optString("key")).put("title", text(it.optString("title"), 80)).put("busy", it.optBoolean("busy")).put("mode", it.optString("mode")).put("status", it.optString("status")).put("workspacePath", text(it.optString("workspacePath"), 256)).put("conversationId", it.optString("conversationId"))
    })
    fun history(value: JSONArray) = JSONArray(value.objects().take(MAX_SESSIONS).map {
        JSONObject().put("id", it.optString("id")).put("title", text(it.optString("title"), 80)).put("updatedAt", it.optString("updatedAt")).put("messageCount", it.optInt("messageCount"))
    })

    fun details(value: JSONObject): JSONObject {
        val full = value.array("messages").objects().filter { it.optString("role") in setOf("user", "assistant") }
        val messages = full.takeLast(MAX_MESSAGES)
        return JSONObject().put("earlierMessages", (full.size - messages.size).coerceAtLeast(0)).put("messages", JSONArray(messages.mapIndexed { index, message ->
            JSONObject().put("id", message.optString("id").ifEmpty { "${message.optString("role")}:$index" })
                .put("role", message.optString("role")).put("content", text(message.optString("content")))
                .put("truncated", message.optString("content").codePointCount(0, message.optString("content").length) > MAX_TEXT)
                .put("reasoningSummary", if (message.optString("reasoningKind") == "summary") text(message.optString("reasoning"), 160) else "")
                .put("attachments", JSONArray(message.array("attachments").objects().take(3).map {
                    JSONObject().put("name", text(it.optString("name"), 48)).put("type", text(it.optString("type"), 24))
                }))
        })).apply {
            value.optJSONObject("session")?.let { session -> put("session", JSONObject().put("busy", session.optBoolean("busy")).put("status", session.optString("status")).put("lastError", text(session.optString("lastError").takeUnless { session.isNull("lastError") }.orEmpty(), 240))) }
            value.optJSONObject("pendingInteraction")?.let { pending ->
                val raw = pending.optJSONObject("payload") ?: JSONObject()
                val payload = JSONObject()
                for (field in listOf("description", "command", "message")) raw.optString(field).takeIf { it.isNotEmpty() }?.let { payload.put(field, text(it, 240)) }
                put("pendingInteraction", JSONObject().put("kind", pending.optString("kind")).put("payload", payload))
            }
            value.optJSONObject("compaction")?.let { put("compaction", JSONObject().put("phase", it.optString("phase")).put("id", it.optString("id"))) }
        }
    }
}
