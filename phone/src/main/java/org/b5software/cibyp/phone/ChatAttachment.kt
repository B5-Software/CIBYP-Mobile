/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LocalAttachment(val uri: Uri, val name: String, val type: String, val size: Long)

fun attachmentInfo(context: Context, uri: Uri): LocalAttachment {
    var name = "attachment"; var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            name = cursor.getString(0) ?: name
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    return LocalAttachment(uri, name, context.contentResolver.getType(uri) ?: "application/octet-stream", size)
}

suspend fun attachmentUpload(context: Context, file: LocalAttachment): JSONObject = withContext(Dispatchers.IO) {
    require(file.size <= 8 * 1024 * 1024) { "Attachments are limited to 8 MiB per file" }
    val bytes = context.contentResolver.openInputStream(file.uri)?.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 8 * 1024 * 1024) { "Attachments are limited to 8 MiB per file" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Cannot read attachment")
    require(bytes.size <= 8 * 1024 * 1024) { "Attachments are limited to 8 MiB per file" }
    JSONObject().put("name", file.name).put("type", file.type).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
}

@Composable fun AttachmentCard(name: String, type: String, size: Long, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (type.startsWith("image/")) Icons.Default.Image else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                val label = name.substringAfterLast('.', "FILE").uppercase()
                Text(label + if (size >= 0) " · ${if (size >= 1048576) String.format(java.util.Locale.US, "%.1f MB", size / 1048576.0) else "${(size / 1024).coerceAtLeast(1)} KB"}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove attachment") }
            else Icon(Icons.Default.Download, "Download")
        }
    }
}
