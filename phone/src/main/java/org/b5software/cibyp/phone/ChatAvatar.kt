/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal suspend fun imageBitmap(source: String): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching {
        if (source.isBlank() || source.length > 12 * 1024 * 1024) return@runCatching null
        val data = if (source.startsWith("data:")) Base64.decode(source.substringAfter(','), Base64.DEFAULT) else null
        if (source.startsWith("<") || source.startsWith("data:image/svg+xml")) {
            val svg = SVG.getFromString(data?.toString(Charsets.UTF_8) ?: source)
            // The source's width/height (100 px for built-in frames) must fill the
            // raster viewport; otherwise it occupies only the top-left 100/192.
            svg.setDocumentWidth(192f)
            svg.setDocumentHeight(192f)
            Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { bitmap ->
                svg.renderToCanvas(android.graphics.Canvas(bitmap))
            }.asImageBitmap()
        } else if (data != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1024).coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
        } else null
    }.getOrNull()
}

@Composable fun ChatAvatar(profile: JSONObject, user: Boolean, babe: Boolean, accent: String) {
    val source = profile.optString("avatar")
    val frame = profile.optString("frame")
    val photo by produceState<ImageBitmap?>(null, source) { value = imageBitmap(source) }
    val overlay by produceState<ImageBitmap?>(null, frame) { value = imageBitmap(frame) }
    val color = runCatching { Color(android.graphics.Color.parseColor(accent)) }.getOrElse { MaterialTheme.colorScheme.primary }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        // Frames use a centered 100×100 viewBox with a 76% inner opening.
        // Match the desktop's 132% frame-to-avatar ratio, without a second ring.
        val avatarSize = if (overlay != null) 48.dp / 1.32f else 38.dp
        val avatarModifier = Modifier.size(avatarSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        Box(if (overlay == null) avatarModifier.border(1.5.dp, color, CircleShape) else avatarModifier, contentAlignment = Alignment.Center) {
            if (photo != null) Image(photo!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (user) Icons.Default.Person else if (babe) Icons.Default.Favorite else Icons.Default.SmartToy, if (user) "You" else "AI", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        if (overlay != null) Image(overlay!!, null, Modifier.fillMaxSize())
    }
}
