package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
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

        // Immediate loading scaffold, then async fetch.
        mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, null))

        val paired = ServerStore.isConfigured(context) && TokenStore.isPaired(context)
        if (!paired) {
            // Not paired: render an empty (0) state; tap bounces to onboarding.
            mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, emptyList()))
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
                mgr.updateAppWidget(appWidgetId, scaffold(context, appWidgetId, tier, active))
            } finally {
                pending.finish()
            }
        }
    }

    private fun tierFor(context: Context, appWidgetId: Int): ActiveDevicesRender.Tier {
        val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return ActiveDevicesRender.sizeTier(minW, minH)
    }

    private fun scaffold(
        context: Context, appWidgetId: Int, tier: ActiveDevicesRender.Tier, active: List<Card>?,
    ): RemoteViews {
        val v = when (tier) {
            ActiveDevicesRender.Tier.LARGE -> ActiveDevicesRender.large(context, active)
            ActiveDevicesRender.Tier.SMALL -> ActiveDevicesRender.small(context, active)
        }
        v.setOnClickPendingIntent(
            R_av_root, PwaLauncher.tapPending(context, appWidgetId, PwaLauncher.Routes.ROOT),
        )
        v.setOnClickPendingIntent(R_av_refresh, refreshPending(context, appWidgetId))
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
    }
}
