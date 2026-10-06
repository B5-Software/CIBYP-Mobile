/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import org.b5software.cibyp.core.RemotePalette
import org.json.JSONObject

@Composable internal fun RemotePhoneTheme(appearance: JSONObject, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val theme = appearance.optJSONObject("theme")
    val colors = if (theme == null) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (systemDark) darkColorScheme() else lightColorScheme()
    } else {
        val p = RemotePalette.from(theme, systemDark)
        (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
            primary = Color(p.accent), onPrimary = Color(p.onAccent),
            primaryContainer = Color(p.container), onPrimaryContainer = Color(p.onContainer),
            secondary = Color(p.accent), onSecondary = Color(p.onAccent),
            secondaryContainer = Color(p.container), onSecondaryContainer = Color(p.onContainer),
            tertiary = Color(p.accent), onTertiary = Color(p.onAccent),
            tertiaryContainer = Color(p.container), onTertiaryContainer = Color(p.onContainer),
            background = Color(p.background), onBackground = Color(p.text),
            surface = Color(p.background), onSurface = Color(p.text),
            surfaceVariant = Color(p.surfaceHigh), onSurfaceVariant = Color(p.mutedText),
            surfaceTint = Color(p.accent), surfaceBright = Color(p.surfaceHigh), surfaceDim = Color(p.background),
            surfaceContainerLowest = Color(p.background), surfaceContainerLow = Color(p.surface),
            surfaceContainer = Color(p.surface), surfaceContainerHigh = Color(p.surfaceHigh),
            surfaceContainerHighest = Color(p.surfaceHigh), outline = Color(p.outline),
            outlineVariant = Color(p.surfaceHigh)
        )
    }
    val darkBars = colors.background.luminance() < 0.5f
    DisposableEffect(context, darkBars) {
        (context as? ComponentActivity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkBars
                isAppearanceLightNavigationBars = !darkBars
            }
        }
        onDispose {}
    }
    MaterialTheme(colorScheme = colors, content = content)
}
