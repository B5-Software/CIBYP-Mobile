/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.b5software.cibyp.core.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable fun UsageContent(usage: JSONObject) {
    val zh = java.util.Locale.getDefault().language == "zh"
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val windows = usage.array("windows").objects()
        if (usage.has("error")) Text(usage.optString("error"), color = MaterialTheme.colorScheme.error)
        windows.forEach { window ->
            val percent = window.optDouble("usedPercent", 0.0).toFloat().coerceIn(0f, 100f)
            val period = when (window.optString("period")) {
                "5hour" -> if (zh) "5 小时" else "5 hours"
                "weekly" -> if (zh) "每周" else "Weekly"
                "monthly" -> if (zh) "每月" else "Monthly"
                else -> window.optString("period")
            }
            Text("${window.optString("label")} · $period", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { percent / 100 }, modifier = Modifier.fillMaxWidth())
            Text("${percent.toInt()}% ${if (zh) "已使用" else "used"}")
            window.optLong("resetsAt").takeIf { it > 0 }?.let {
                Text("${if (zh) "重置：" else "Resets: "}${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall)
            }
        }
        val cost = usage.optJSONObject("daily") ?: usage.optJSONObject("equivalent")
        cost?.let {
            Text(if (usage.optBoolean("subscription")) { if (zh) "API 等效消费" else "API equivalent cost" } else { if (zh) "今日消费" else "Today's cost" }, style = MaterialTheme.typography.titleSmall)
            Text("$${String.format(java.util.Locale.US, "%.4f", it.optDouble("costUSD", 0.0))}")
        }
        if (windows.isEmpty() && cost == null && !usage.has("error")) Text(if (zh) "没有可用的额度数据" else "No usage data available")
    }
}
