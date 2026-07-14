package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.Card
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
        // The bound set shrank → refresh the native SSE watch-set (#48).
        cloud.dopp.solaris.realtime.WatchSet.postCurrentAsync(context)
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
        // A render/data failure must yield a clean fallback, not an uncaught throw
        // that leaves Android showing "Widget kann nicht geladen werden" (#32).
        val entityId = try {
            WidgetStore.entityId(context, appWidgetId)
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
            return
        }
        if (entityId == null) {
            // Not configured (or orphaned/unbound) — tapping opens the picker.
            try {
                mgr.updateAppWidget(
                    appWidgetId,
                    WidgetRender.build(context, appWidgetId, null, "—", "", configPending(context, appWidgetId)),
                )
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
            }
            return
        }
        val tap = tapPending(context, appWidgetId, entityId)
        val domain = WidgetStore.domain(context, appWidgetId)
        // Immediate render from cache, then async live-state fetch.
        try {
            mgr.updateAppWidget(
                appWidgetId,
                WidgetRender.build(context, appWidgetId, null, WidgetStore.name(context, appWidgetId), domain, tap),
            )
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
        }
        val pending = goAsync()
        thread {
            try {
                val api = ApiClient(context.applicationContext)
                val card = try { api.getCard(entityId) } catch (e: Exception) { null }
                mgr.updateAppWidget(
                    appWidgetId,
                    WidgetRender.build(
                        context, appWidgetId, card,
                        WidgetStore.name(context, appWidgetId), domain, tap,
                    ),
                )
            } catch (t: Throwable) {
                // Any render/transaction failure (e.g. oversized bitmap) → clean fallback.
                WidgetFallback.show(context, appWidgetId, safeRefreshTap(context, appWidgetId))
            } finally {
                pending?.finish()
            }
        }
    }

    /** A tap [PendingIntent] that re-runs this instance's refresh (fallback retry). */
    private fun safeRefreshTap(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, DeviceWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 900_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun tapPending(context: Context, appWidgetId: Int, entityId: String): PendingIntent =
        // Name/body tap opens this bound entity's card in the PWA Custom Tab
        // (#27, route confirmed in solarisbay#769).
        PwaLauncher.tapPending(context, appWidgetId, PwaLauncher.Routes.device(entityId))

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

        /**
         * Render one bound instance directly from an already-known [Card] — the
         * realtime `card_state` push path (#48): no re-fetch, we already have the
         * fresh state. Mirrors the async branch of [refresh] but skips the network.
         * Any failure falls back cleanly so a bad push never breaks the widget.
         */
        fun renderCard(context: Context, appWidgetId: Int, card: Card) {
            val mgr = AppWidgetManager.getInstance(context)
            val entityId = WidgetStore.entityId(context, appWidgetId) ?: return
            val tap = PwaLauncher.tapPending(
                context, appWidgetId, PwaLauncher.Routes.device(entityId),
            )
            val domain = WidgetStore.domain(context, appWidgetId)
            try {
                mgr.updateAppWidget(
                    appWidgetId,
                    WidgetRender.build(
                        context, appWidgetId, card,
                        WidgetStore.name(context, appWidgetId), domain, tap,
                    ),
                )
            } catch (t: Throwable) {
                requestRefresh(context, appWidgetId)
            }
        }

        /**
         * Wake every device-widget bound to [entityId] with the pushed [card] (#48).
         * Iterates this provider's live instances and renders the matching ones from
         * the payload — no re-fetch. Returns the count updated.
         */
        fun wakeEntity(context: Context, entityId: String, card: Card): Int {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, DeviceWidgetProvider::class.java))
            var n = 0
            for (id in ids) {
                if (WidgetStore.entityId(context, id) == entityId) {
                    renderCard(context, id, card)
                    n++
                }
            }
            return n
        }
    }
}
