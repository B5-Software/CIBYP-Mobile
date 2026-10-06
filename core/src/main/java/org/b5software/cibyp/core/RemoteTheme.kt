/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.core

import org.json.JSONObject
import kotlin.math.pow

/** Only public appearance data is shared with a watch; never forward settings. */
fun publicRemoteTheme(state: JSONObject, fallback: JSONObject = JSONObject()): JSONObject {
    val theme = state.optJSONObject("theme") ?: fallback
    val mode = theme.optString("mode", "system").takeIf { it in setOf("light", "dark", "system") } ?: "system"
    val resolved = if (mode == "system") {
        if (state.has("shouldUseDarkColors")) { if (state.optBoolean("shouldUseDarkColors", false)) "dark" else "light" }
        else fallback.optString("resolvedMode", "light").takeIf { it in setOf("dark", "light") } ?: "light"
    } else mode
    return JSONObject().put("mode", mode).put("resolvedMode", resolved).apply {
        for (name in listOf("accentColor", "backgroundColor")) {
            theme.optString(name).takeIf { parseThemeColor(it) != null }?.let { put(name, it) }
        }
    }
}

fun parseThemeColor(raw: String): Long? {
    if (!Regex("^#[0-9a-fA-F]{6}$").matches(raw)) return null
    return 0xff000000L or raw.substring(1).toLong(16)
}

fun blendThemeColor(background: Long, foreground: Long, amount: Double): Long {
    val mix = amount.coerceIn(0.0, 1.0)
    var result = 0xff000000L
    for (shift in listOf(16, 8, 0)) {
        val channel = (((background shr shift) and 255) * (1 - mix) + ((foreground shr shift) and 255) * mix).toLong()
        result = result or (channel shl shift)
    }
    return result
}

fun themeLuminance(color: Long): Double {
    fun channel(shift: Int): Double {
        val value = ((color shr shift) and 255) / 255.0
        return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
    }
    return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
}

fun contrastThemeText(background: Long): Long =
    if (themeLuminance(background) > .179) 0xff000000L else 0xffffffffL

data class RemotePalette(val dark: Boolean, val accent: Long, val background: Long) {
    val text = contrastThemeText(background)
    val onAccent = contrastThemeText(accent)
    val mutedText = blendThemeColor(background, text, .76)
    val container = blendThemeColor(background, accent, if (dark) .25 else .13)
    val onContainer = contrastThemeText(container)
    val surface = blendThemeColor(background, text, .045)
    val surfaceHigh = blendThemeColor(background, text, .085)
    val outline = blendThemeColor(background, text, .45)
    companion object {
        fun from(theme: JSONObject, systemDark: Boolean): RemotePalette {
            val dark = when (theme.optString("resolvedMode", theme.optString("mode"))) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            return RemotePalette(dark, parseThemeColor(theme.optString("accentColor")) ?: 0xff4f8cffL,
                parseThemeColor(theme.optString("backgroundColor")) ?: if (dark) 0xff1a2232L else 0xfff5f7faL)
        }
    }
}
