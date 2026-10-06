package org.b5software.cibyp.phone

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.NumberFormat

private fun tr(zh: String, en: String) = if (java.util.Locale.getDefault().language == "zh") zh else en

@Composable fun CompactionStatus(state: JSONObject, animations: Boolean = true) {
    val phase = state.optString("phase")
    if (phase !in listOf("running", "done", "error")) return
    val id = state.optString("id")
    val finishedAt = state.optLong("finishedAt")
    val lifetime = if (phase == "error") 12000L else 7000L
    var visible by remember(id, phase) { mutableStateOf(finishedAt == 0L || System.currentTimeMillis() - finishedAt < lifetime) }
    LaunchedEffect(id, phase, finishedAt) {
        if (finishedAt > 0) {
            delay((lifetime - (System.currentTimeMillis() - finishedAt)).coerceAtLeast(0))
            visible = false
        }
    }
    AnimatedVisibility(visible, enter = if (animations) fadeIn(tween(180)) else EnterTransition.None, exit = if (animations) fadeOut(tween(180)) else ExitTransition.None) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (phase == "running") {
                if (animations) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else CircularProgressIndicator(progress = { 0.35f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            else Icon(if (phase == "error") Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = if (phase == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Column {
                Text(when (phase) {
                    "running" -> tr("正在压缩上下文", "Compacting context")
                    "done" -> tr("上下文已压缩", "Context compacted")
                    else -> tr("压缩失败 · 上下文已保留", "Compaction failed · context preserved")
                }, style = MaterialTheme.typography.labelLarge)
                if (phase == "done") Text(NumberFormat.getIntegerInstance().format(state.optLong("beforeTokens")) + " → " + NumberFormat.getIntegerInstance().format(state.optLong("afterTokens")) + " tokens", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    }
}
