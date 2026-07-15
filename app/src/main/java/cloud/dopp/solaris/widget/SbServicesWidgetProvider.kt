package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.SbApiClient
import kotlin.concurrent.thread

/**
 * ServiceBay **service-status summary** widget (#44): three counts — healthy /
 * warning / failure — from Solaris' aggregated `/napi/servicebay/services`
 * (BFF, ADR 0010), bucketed by [SbRender]. Deliberately NOT a per-service list and
 * NOT a control surface (start/stop hinges on solarisbay#827). Read-only; a tap
 * opens the PWA.
 */
class SbServicesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
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

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val tap = PwaLauncher.tapPending(context, 340_000 + appWidgetId, PwaLauncher.Routes.SERVICEBAY)
        try {
            mgr.updateAppWidget(appWidgetId, render(context, appWidgetId, null, tap))
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, tap)
            return
        }
        val pending = goAsync()
        thread {
            try {
                val services = try {
                    SbApiClient(context.applicationContext).listServices()
                } catch (e: Exception) {
                    null
                }
                val counts = services?.let { SbRender.counts(it) }
                mgr.updateAppWidget(appWidgetId, render(context, appWidgetId, counts, tap))
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, tap)
            } finally {
                pending?.finish()
            }
        }
    }

    private fun render(
        context: Context,
        appWidgetId: Int,
        counts: Triple<Int, Int, Int>?,
        tap: PendingIntent,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_sb_services)
        v.setOnClickPendingIntent(R.id.svc_root, tap)
        v.setOnClickPendingIntent(R.id.svc_refresh, refreshPending(context, appWidgetId))
        if (counts == null) {
            v.setTextViewText(R.id.svc_ok, "—")
            v.setTextViewText(R.id.svc_warn, "—")
            v.setTextViewText(R.id.svc_fail, "—")
        } else {
            v.setTextViewText(R.id.svc_ok, counts.first.toString())
            v.setTextViewText(R.id.svc_warn, counts.second.toString())
            v.setTextViewText(R.id.svc_fail, counts.third.toString())
        }
        return v
    }

    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, SbServicesWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 340_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.SB_SERVICES_REFRESH"
    }
}
