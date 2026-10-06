/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.wear

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.LocalContentColor
import org.b5software.cibyp.core.RemotePalette
import org.json.JSONObject

@Composable internal fun remoteWatchColors(appearance: JSONObject): ColorScheme {
    val theme = appearance.optJSONObject("theme")
    val systemDark = isSystemInDarkTheme()
    return remember(theme?.toString(), systemDark) {
    if (theme == null) return@remember ColorScheme()
    val p = RemotePalette.from(theme, systemDark)
    ColorScheme().copy(
        primary = Color(p.accent), primaryDim = Color(p.container),
        primaryContainer = Color(p.container), onPrimary = Color(p.onAccent), onPrimaryContainer = Color(p.onContainer),
        secondary = Color(p.accent), secondaryDim = Color(p.container),
        secondaryContainer = Color(p.container), onSecondary = Color(p.onAccent), onSecondaryContainer = Color(p.onContainer),
        tertiary = Color(p.accent), tertiaryDim = Color(p.container),
        tertiaryContainer = Color(p.container), onTertiary = Color(p.onAccent), onTertiaryContainer = Color(p.onContainer),
        background = Color(p.background), onBackground = Color(p.text),
        surfaceContainerLow = Color(p.background), surfaceContainer = Color(p.surface), surfaceContainerHigh = Color(p.surfaceHigh),
        onSurface = Color(p.text), onSurfaceVariant = Color(p.mutedText),
        outline = Color(p.outline), outlineVariant = Color(p.surfaceHigh)
    )
    }
}

@Composable internal fun RemoteWatchTheme(appearance: JSONObject, content: @Composable () -> Unit) {
    val colors = remoteWatchColors(appearance)
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
