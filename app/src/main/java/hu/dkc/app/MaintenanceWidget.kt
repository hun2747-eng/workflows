package hu.dkc.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 1×1 főképernyő widget: zöld mezőben az esedékes karbantartások száma (admin). */
class MaintenanceWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, views(context)) }
    }

    companion object {
        fun updateAll(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, MaintenanceWidget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val admin = context.getSharedPreferences("dkc_prefs", Context.MODE_PRIVATE).getBoolean("admin_logged_in", false)
            val n = Maintenance.count(context)
            val v = RemoteViews(context.packageName, R.layout.widget_maintenance)
            v.setTextViewText(R.id.wCount, if (admin && n >= 0) n.toString() else "–")
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_URL, if (admin) Maintenance.URL else BuildConfig.HOME_URL)
            }
            v.setOnClickPendingIntent(
                R.id.wRoot,
                PendingIntent.getActivity(context, 4300, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            return v
        }
    }
}
