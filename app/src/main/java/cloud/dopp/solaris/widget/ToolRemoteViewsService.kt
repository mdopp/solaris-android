package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cloud.dopp.solaris.R

/**
 * Backs the scrollable ListView of the generic tool widget (#70) — the #28
 * collection pattern, one row per mapped [ToolCell].
 *
 * The factory is deliberately dumb: [ToolWidgetProvider] does the fetching and the
 * schema→cell mapping and persists the result per instance, so a redraw is
 * instant and survives process death. Which slots a row shows is decided entirely
 * by the tool's `tool-cell-schema` — an unused role simply arrives as null here
 * and its view is hidden.
 */
class ToolRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return ToolCellFactory(applicationContext, appWidgetId)
    }
}

private class ToolCellFactory(
    private val ctx: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var cells: List<ToolCell> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        cells = ToolCells.decode(WidgetStore.toolCellsJson(ctx, appWidgetId))
    }

    override fun onDestroy() {
        cells = emptyList()
    }

    override fun getCount(): Int = cells.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = RemoteViews(ctx.packageName, R.layout.item_tool_row)
        val cell = cells.getOrNull(position)
            ?: return row.also { it.setViewVisibility(R.id.tw_item_root, View.GONE) }

        row.setTextViewText(R.id.tw_item_title, cell.title)

        // subtitle + meta share the one muted detail line; both optional roles.
        val detail = listOfNotNull(cell.subtitle, cell.meta).joinToString(ToolCells.META_SEP)
        if (detail.isBlank()) {
            row.setViewVisibility(R.id.tw_item_meta, View.GONE)
        } else {
            row.setViewVisibility(R.id.tw_item_meta, View.VISIBLE)
            row.setTextViewText(R.id.tw_item_meta, detail)
        }

        if (cell.badge.isNullOrBlank()) {
            row.setViewVisibility(R.id.tw_item_badge, View.GONE)
        } else {
            row.setViewVisibility(R.id.tw_item_badge, View.VISIBLE)
            row.setTextViewText(R.id.tw_item_badge, cell.badge)
        }

        // Tap → the PWA (merged with the provider's PendingIntentTemplate). Acting
        // on the row in place needs the action's params, which the catalog doesn't
        // declare yet (solarisbay#1214), so the web card stays the place to act.
        val fill = Intent().putExtra(PwaTrampolineActivity.EXTRA_PATH, PwaLauncher.Routes.ROOT)
        row.setOnClickFillInIntent(R.id.tw_item_root, fill)
        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
