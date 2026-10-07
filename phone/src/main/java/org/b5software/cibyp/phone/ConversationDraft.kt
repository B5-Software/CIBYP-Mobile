/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.net.Uri
import org.b5software.cibyp.core.*
import org.json.*

data class ConversationDraft(val text: String = "", val attachments: List<LocalAttachment> = emptyList()) {
    fun json() = JSONObject().put("text", text).put("attachments", JSONArray(attachments.map {
        JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("type", it.type).put("size", it.size)
    }))
    companion object {
        fun from(value: JSONObject) = ConversationDraft(value.optString("text"), value.array("attachments").objects().take(10).map {
            LocalAttachment(Uri.parse(it.getString("uri")), it.getString("name"), it.optString("type"), it.optLong("size", -1))
        })
    }
}
