package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R

/**
 * The **1×1 tool launcher tile** (#71, archetypes B and C of #69): a button that
 * deep-links into the PWA for one `.tool` — either its create screen
 * (`tool-compose-path`) or the chat with its card already open (`#/?tool=<id>`).
 * Both routes are catalog-driven (solarisbay#1213), so a `.tool` installed after
 * this build gets a working tile with no app update.
 *
 * The cheapest surface in the family: nothing is fetched, nothing is rendered from
 * a schema, so [onUpdate] only re-binds the tap. The route itself was resolved once
 * by [ToolLauncherConfigActivity] and persisted — see [WidgetStore.bindToolLaunch].
 */
class ToolLauncherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.unbindToolLaunch(context, it) }
    }

    companion object {

        /** Draw one tile — bound or not. Also the config screen's "show it now" path. */
        fun render(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val path = WidgetStore.toolLaunchPath(context, appWidgetId)
            val mode = WidgetStore.toolLaunchMode(context, appWidgetId)
            val bound = WidgetStore.toolLaunchId(context, appWidgetId) != null && path != null
            val label = WidgetStore.toolLaunchLabel(context, appWidgetId)
                .ifBlank { context.getString(R.string.tool_launch_label) }
            // An unbound tile (pinned from the app, or a cancelled config) opens its
            // own picker (#105). It used to deep-link into the PWA start page —
            // visibly *something*, but never the thing the tile is missing. Same
            // rule as the empty 1×1 device tile (#104), and armed on the children
            // because a launcher does not reliably deliver a click on the root.
            val tap = if (bound) {
                PwaLauncher.tapPending(context, 600_000 + appWidgetId, path!!)
            } else {
                configPending(context, appWidgetId)
            }
            val v = RemoteViews(context.packageName, R.layout.widget_tool_launcher)
            v.setImageViewResource(R.id.tl_icon, if (bound) mode.iconRes else R.drawable.ic_plus)
            v.setTextViewText(R.id.tl_label, label)
            v.setTextViewText(
                R.id.tl_cta,
                context.getString(if (bound) mode.ctaRes else R.string.tool_launch_setup),
            )
            v.setViewVisibility(R.id.tl_cta, View.VISIBLE)
            for (id in TAP_TARGETS) v.setOnClickPendingIntent(id, tap)
            try {
                mgr.updateAppWidget(appWidgetId, v)
            } catch (t: Throwable) {
                WidgetFallback.show(context, appWidgetId, tap)
            }
        }

        /** Re-render one instance (used by the config screen after binding). */
        fun requestRefresh(context: Context, appWidgetId: Int) {
            render(context, AppWidgetManager.getInstance(context), appWidgetId)
        }

        /** Every view the tap is armed on — the tile is one button, root to label. */
        val TAP_TARGETS = listOf(R.id.tl_root, R.id.tl_icon, R.id.tl_label, R.id.tl_cta)

        /** The unbound tile's way out: its own picker, not the PWA (#105). */
        private fun configPending(context: Context, appWidgetId: Int): PendingIntent {
            val i = Intent(context, ToolLauncherConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context, 610_000 + appWidgetId, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
