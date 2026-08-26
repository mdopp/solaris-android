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
 * and its view is hidden. That now includes the action button (#90): the cell
 * arrives with its callback body already resolved, or with none at all.
 */
class ToolRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return ToolCellFactory(applicationContext, appWidgetId)
    }
}

/**
 * The two fill-in intents of a tool row (#90) — the only thing that tells the row
 * body and the action button apart, because a collection has exactly one
 * `PendingIntentTemplate` and both taps arrive at [ToolActionActivity].
 *
 * Split out of the factory so a JVM test can assert the rule that ticket turns on:
 * only the **button's** fill-in carries [ToolActionActivity.EXTRA_ACTION_ID], so a
 * press that lands on the row body can never run the action, and a press on the
 * button can never fall through to the PWA. (Whether the press *lands* on the
 * button is the layout's job — `item_tool_row.xml`, `tw_item_action`.)
 */
object ToolRow {

    /** Row body → open the item's tool in the PWA. No action extras, ever. */
    fun bodyFillIn(): Intent =
        Intent().putExtra(ToolActionActivity.EXTRA_PATH, PwaLauncher.Routes.ROOT)

    /**
     * The action button's fill-in, or `null` when this row has no action to offer
     * — the caller hides the button then, rather than showing a dead one.
     */
    fun actionFillIn(cell: ToolCell, appWidgetId: Int): Intent? {
        if (cell.actionId.isNullOrBlank()) return null
        return Intent()
            .putExtra(ToolActionActivity.EXTRA_ACTION_ID, cell.actionId)
            .putExtra(ToolActionActivity.EXTRA_PARAMS, cell.actionParams.orEmpty())
            .putExtra(ToolActionActivity.EXTRA_TITLE, cell.title)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
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

        // Body tap → the PWA, unchanged: the web card stays the place to read and
        // edit an item. Both fill-ins merge into the same template
        // (ToolActionActivity), which tells them apart by the action extras.
        row.setOnClickFillInIntent(R.id.tw_item_root, ToolRow.bodyFillIn())

        // The action button (#90) appears only for a row whose action the catalog
        // declared params for and whose fields actually filled them — a tool
        // without `tool-action-params` shows no empty placeholder.
        val action = ToolRow.actionFillIn(cell, appWidgetId)
        if (action == null) {
            row.setViewVisibility(R.id.tw_item_action, View.GONE)
        } else {
            row.setViewVisibility(R.id.tw_item_action, View.VISIBLE)
            row.setOnClickFillInIntent(R.id.tw_item_action, action)
        }
        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
