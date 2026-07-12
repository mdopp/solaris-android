package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Energy statistics widget (#9): a 48h trend of the house power draw, rendered
 * as a sparkline ([ChartRenderer]) from `/napi/portal/entity-history`. The house
 * entity is discovered from `/napi/portal/energy` — so live data arrives with
 * solarisbay#762; until then it shows an honest "no data" state.
 */
class EnergyStatsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) = refresh(context, appWidgetId)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun base(context: Context, hint: String, tap: PendingIntent): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_energy_stats)
        v.setOnClickPendingIntent(R.id.es_root, tap)
        v.setTextViewText(R.id.es_title, context.getString(R.string.stats_title))
        v.setViewVisibility(R.id.es_chart, View.GONE)
        v.setTextViewText(R.id.es_hint, hint)
        return v
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val paired = ServerStore.isConfigured(context) && TokenStore.isPaired(context)
        val tap = tapPending(context, appWidgetId, paired)
        if (!paired) {
            mgr.updateAppWidget(appWidgetId, base(context, context.getString(R.string.stats_pair), tap))
            return
        }
        mgr.updateAppWidget(appWidgetId, base(context, context.getString(R.string.stats_loading), tap))
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(context.applicationContext)
                val houseEid = try { api.getEnergy()?.house?.entityId } catch (e: Exception) { null }
                val series = if (houseEid != null) {
                    try { api.getEntityHistory(houseEid, "48h") } catch (e: Exception) { emptyList() }
                } else {
                    emptyList()
                }
                val v = RemoteViews(context.packageName, R.layout.widget_energy_stats)
                v.setOnClickPendingIntent(R.id.es_root, tap)
                v.setTextViewText(R.id.es_title, context.getString(R.string.stats_title))
                if (series.size >= 2) {
                    v.setImageViewBitmap(
                        R.id.es_chart,
                        ChartRenderer.sparkline(560, 220, series, LINE, FILL),
                    )
                    v.setViewVisibility(R.id.es_chart, View.VISIBLE)
                    v.setTextViewText(R.id.es_hint, summary(series))
                } else {
                    v.setViewVisibility(R.id.es_chart, View.GONE)
                    v.setTextViewText(R.id.es_hint, context.getString(R.string.stats_nodata))
                }
                mgr.updateAppWidget(appWidgetId, v)
            } finally {
                pending.finish()
            }
        }
    }

    private fun summary(series: List<Float>): String {
        val now = fmt(series.last().toDouble())
        val max = fmt(series.max().toDouble())
        return "jetzt $now · max $max"
    }

    private fun fmt(w: Double): String {
        val a = abs(w)
        return if (a >= 1000) String.format(Locale.GERMANY, "%.1f kW", a / 1000.0)
        else String.format(Locale.GERMANY, "%.0f W", a)
    }

    private fun tapPending(context: Context, appWidgetId: Int, paired: Boolean): PendingIntent {
        val intent = if (paired) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, OnboardingHomeActivity::class.java)
        } else {
            Intent(context, OnboardingHomeActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val LINE = 0xFF66BB6A.toInt()
        private const val FILL = 0x3366BB6A
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.STATS_REFRESH"
    }
}
