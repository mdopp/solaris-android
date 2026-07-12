package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.Card
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import kotlin.concurrent.thread

/**
 * The household **active-devices overview** widget (#28): size-adaptive: small =
 * a compact count ("3 an"); large = a bounded list of the currently on/open
 * devices (icon + name + state), styled like the picker rows. Not bound to a
 * single entity — it reads the whole roster and filters to the active ones via
 * [ApiClient.listActive]. Tap → PWA overview ([PwaLauncher]); a discreet refresh
 * icon re-fetches.
 */
class ActiveDevicesWidgetProvider : AppWidgetProvider() {

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

    private fun refresh(context: Context, appWidgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val tier = tierFor(context, appWidgetId)

        // Immediate loading scaffold, then async fetch. A render failure must yield
        // a clean fallback, not an uncaught throw ("kann nicht geladen werden", #32).
        try {
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, null))
        } catch (t: Throwable) {
            WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
        }

        val paired = ServerStore.isConfigured(context) && TokenStore.isPaired(context)
        if (!paired) {
            // Not paired: render an empty (0) state; tap bounces to onboarding.
            try {
                WidgetStore.setActiveCacheJson(context, ActiveCache.encode(emptyList()))
                mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, emptyList()))
                if (tier == ActiveDevicesRender.Tier.LARGE) {
                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R_av_list)
                }
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            }
            return
        }

        val pending = goAsync()
        thread {
            try {
                val active = try {
                    ApiClient(context.applicationContext).listActive()
                } catch (e: Exception) {
                    null
                }
                // Persist the fresh list so the collection factory renders it (stale→fresh),
                // then tell the ListView to reload. The real latency fix is a server-side
                // batch/active endpoint (solarisbay#773); this cache only hides the wait.
                if (active != null) {
                    WidgetStore.setActiveCacheJson(context, ActiveCache.encode(active))
                }
                mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, active))
                if (tier == ActiveDevicesRender.Tier.LARGE) {
                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R_av_list)
                }
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, refreshPending(context, appWidgetId))
            } finally {
                pending.finish()
            }
        }
    }

    private fun tierFor(context: Context, appWidgetId: Int): ActiveDevicesRender.Tier =
        ActiveDevicesRender.sizeTier(minWidth(context, appWidgetId), minHeight(context, appWidgetId))

    private fun minWidth(context: Context, appWidgetId: Int): Int =
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)

    private fun minHeight(context: Context, appWidgetId: Int): Int =
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

    private fun scaffold(
        context: Context, appWidgetId: Int, tier: ActiveDevicesRender.Tier, active: List<Card>?,
    ): RemoteViews {
        val v = when (tier) {
            ActiveDevicesRender.Tier.LARGE -> largeCollection(context, appWidgetId, active)
            ActiveDevicesRender.Tier.SMALL -> ActiveDevicesRender.small(context, active)
        }
        v.setOnClickPendingIntent(
            R_av_root, PwaLauncher.tapPending(context, appWidgetId, PwaLauncher.Routes.ROOT),
        )
        v.setOnClickPendingIntent(R_av_refresh, refreshPending(context, appWidgetId))
        return v
    }

    /**
     * Build the large tier as a scrollable collection (#28): header + a ListView
     * bound to [ActiveDevicesRemoteViewsService] via setRemoteAdapter, an empty view,
     * and a PendingIntentTemplate so per-row fill-in taps open the PWA. The service
     * intent carries the appWidgetId (unique data Uri) so distinct instances don't
     * share an adapter.
     */
    private fun largeCollection(context: Context, appWidgetId: Int, active: List<Card>?): RemoteViews {
        val v = ActiveDevicesRender.large(context, active)

        val svc = Intent(context, ActiveDevicesRemoteViewsService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .setData(Uri.parse("solaris://active/$appWidgetId"))
        v.setRemoteAdapter(R_av_list, svc)
        v.setEmptyView(R_av_list, R_av_empty)

        // Template merged with each row's fill-in intent (set in the factory) → tap → PWA.
        v.setPendingIntentTemplate(
            R_av_list,
            PwaLauncher.rowTapTemplate(context, appWidgetId),
        )
        return v
    }

    private fun refreshPending(context: Context, appWidgetId: Int): PendingIntent {
        val i = Intent(context, ActiveDevicesWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        return PendingIntent.getBroadcast(
            context, 200_000 + appWidgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REFRESH = "cloud.dopp.solaris.widget.ACTIVE_REFRESH"

        // Shared ids across both layouts (small + large use the same root/refresh ids).
        private val R_av_root = cloud.dopp.solaris.R.id.av_root
        private val R_av_refresh = cloud.dopp.solaris.R.id.av_refresh

        // Large-tier collection ids.
        private val R_av_list = cloud.dopp.solaris.R.id.av_list
        private val R_av_empty = cloud.dopp.solaris.R.id.av_empty
    }
}
