/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.b5software.cibyp.phone
import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.widget.RemoteViews

class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val repository = (context.applicationContext as RemoteApp).remote
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.widget_status, repository.active.value?.name ?: context.getString(R.string.open))
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(manager.getAppWidgetIds(ComponentName(context, RemoteWidget::class.java)), views)
        }
    }
}
