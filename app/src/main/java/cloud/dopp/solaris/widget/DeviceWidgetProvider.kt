package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import cloud.dopp.solaris.data.ApiClient
import kotlin.concurrent.thread

/**
 * The universal, per-instance device widget. Each instance is bound (via
 * [WidgetConfigActivity]) to one HA entity; it renders that entity's live state
 * and a tap runs the appropriate service (toggle / open-close, garage confirmed
 * by [WidgetActionActivity]).
 */
class DeviceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetId)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.unbind(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, id)
        }
    }

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val entityId = WidgetStore.entityId(context, appWidgetId)
        if (entityId == null) {
            // Not configured yet — tapping opens the picker.
            mgr.updateAppWidget(
                appWidgetId,
                WidgetRender.build(context, appWidgetId, null, "—", "", configPending(context, appWidgetId)),
            )
            return
        }
        val tap = tapPending(context, appWidgetId)
        val domain = WidgetStore.domain(context, appWidgetId)
        // Immediate render from cache, then async live-state fetch.
        mgr.updateAppWidget(
            appWidgetId,
            WidgetRender.build(context, appWidgetId, null, WidgetStore.name(context, appWidgetId), domain, tap),
        )
        val pending = goAsync()
        thread {
            try {
                val card = try {
                    ApiClient(context.applicationContext).getCard(entityId)
                } catch (e: Exception) {
                    null
                }
                mgr.updateAppWidget(
                    appWidgetId,
                    WidgetRender.build(context, appWidgetId, card, WidgetStore.name(context, appWidgetId), domain, tap),
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun tapPending(context: Context, appWidgetId: Int): PendingIntent {
        // A broadcast (no Activity) so a tap runs headless without flashing a screen.
        val i = Intent(context, WidgetActionReceiver::class.java)
            .setAction(WidgetActionReceiver.ACTION_TAP)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun configPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 100_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.REFRESH"

        /** Ask a bound instance to re-fetch and re-render (explicit self-broadcast). */
        fun requestRefresh(context: Context, appWidgetId: Int) {
            val i = Intent(context, DeviceWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            context.sendBroadcast(i)
        }
    }
}
