package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
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

        /** Draw one bound tile. Also the config screen's "show it now" path. */
        fun render(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
            val path = WidgetStore.toolLaunchPath(context, appWidgetId)
            val mode = WidgetStore.toolLaunchMode(context, appWidgetId)
            val label = WidgetStore.toolLaunchLabel(context, appWidgetId)
                .ifBlank { context.getString(R.string.tool_launch_label) }
            // An unbound tile (config cancelled) still opens the PWA start page
            // rather than doing nothing — the dead-end guard the routes have too.
            val tap = PwaLauncher.tapPending(
                context, 600_000 + appWidgetId, path ?: PwaLauncher.Routes.TOOL_START,
            )
            val v = RemoteViews(context.packageName, R.layout.widget_tool_launcher)
            v.setImageViewResource(R.id.tl_icon, mode.iconRes)
            v.setTextViewText(R.id.tl_label, label)
            v.setTextViewText(R.id.tl_cta, context.getString(mode.ctaRes))
            v.setViewVisibility(R.id.tl_cta, View.VISIBLE)
            v.setOnClickPendingIntent(R.id.tl_root, tap)
            v.setOnClickPendingIntent(R.id.tl_icon, tap)
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
    }
}
