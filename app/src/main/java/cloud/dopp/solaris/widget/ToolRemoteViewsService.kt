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
 * and its view is hidden. The cell also arrives with its action's callback body
 * already resolved (#90) and with the id its def declared (#107), so a row tap
 * assembles its sheet without touching the catalog.
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
 * A tool row's **one** tap target and what the tap offers (#90/#107).
 *
 * Three attempts put the action on a button *inside* the row and all three lost
 * the press on the device: first attributes (`clickable`, a 48dp target), then
 * structure (no fill-in on the row root at all, text column and button as
 * siblings with one fill-in each). The last one is disproved by the app itself —
 * [ToolActionActivity] opens the PWA **only** when no action id arrived, so
 * "Solaris opened" means the button's fill-in never came. Android documents
 * several click targets per collection row; this launcher evidently delivers one.
 *
 * So the row carries exactly one fill-in again, and the two things it can offer
 * are entries of a sheet instead of two targets: the check mark is **gone from
 * the layout**, and a tap opens [ActionSheets.toolRow]. One tap more, and it does
 * what it promises — the same shape the lock chooser (#92) uses, which is the one
 * pattern proven to work on this device.
 *
 * Split out of the factory so the assembly is JVM-testable: which entries a row
 * offers, and which route the open entry aims at.
 */
object ToolRow {

    /** The row's single tap target — the whole row, nothing nested inside it. */
    val TAP_TARGET = R.id.tw_item_root

    /**
     * The one fill-in that completes the collection's template (#90). It carries
     * everything the tap needs to assemble its sheet without a catalog lookup: the
     * item route when the def declared an id field, the action when the def
     * declared params this row could fill, and the row's title for the wording.
     */
    fun fillIn(cell: ToolCell, toolId: String?, appWidgetId: Int): Intent {
        val i = Intent()
            .putExtra(ToolActionActivity.EXTRA_TITLE, cell.title)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        itemPath(toolId, cell.itemId)?.let { i.putExtra(ToolActionActivity.EXTRA_PATH, it) }
        if (!cell.actionId.isNullOrBlank()) {
            i.putExtra(ToolActionActivity.EXTRA_ACTION_ID, cell.actionId)
            i.putExtra(ToolActionActivity.EXTRA_PARAMS, cell.actionParams.orEmpty())
        }
        return i
    }

    /**
     * Where this row's "open" entry goes (#107), or **null** when there is no such
     * entry to offer: the tool declared no `tool-item-id-field` (`note`, `home`,
     * `energy`), the row doesn't carry the declared field, or the value can't ride
     * in a route segment. A tool without the declaration gets no open entry rather
     * than an address invented for it — the whole point of solarisbay#1256 is that
     * the field is read, not guessed.
     *
     * Until #1256 is on solarisbay's `main` the server doesn't know the route yet
     * and the PWA falls through to the chat view — no worse than today's landing
     * on the start page, and it improves by itself with their merge.
     */
    fun itemPath(toolId: String?, itemId: String?): String? =
        PwaLauncher.Routes.toolItem(toolId, itemId)
            .takeIf { it != PwaLauncher.Routes.TOOL_START }

    /** The entries a tap on this row offers, in the order the sheet lists them. */
    fun choices(hasAction: Boolean, itemPath: String?): List<ToolRowChoice> = buildList {
        if (hasAction) add(ToolRowChoice.RUN)
        if (!itemPath.isNullOrBlank()) add(ToolRowChoice.OPEN)
    }

    /**
     * Does the tap have to ask before it does anything? Only when it could
     * **change** something. A tap that can merely navigate just navigates — a
     * sheet whose single entry is "Eintrag öffnen" would cost a tap and answer a
     * question nobody asked — while a tap that could tick off a task must never
     * fire on a mis-touch.
     */
    fun asksFirst(choices: List<ToolRowChoice>): Boolean = ToolRowChoice.RUN in choices
}

private class ToolCellFactory(
    private val ctx: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var cells: List<ToolCell> = emptyList()

    /** The tool this instance is bound to — half of a row's item route (#107). */
    private var toolId: String? = null

    override fun onCreate() {}

    override fun onDataSetChanged() {
        cells = ToolCells.decode(WidgetStore.toolCellsJson(ctx, appWidgetId))
        toolId = WidgetStore.toolId(ctx, appWidgetId)
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

        // One tap target for the whole row (#90). What the tap then offers — run
        // the declared action, open the item's card, or simply open the PWA when
        // it offers neither — is decided in ToolActionActivity off these extras,
        // because a collection has exactly one PendingIntentTemplate and this
        // launcher delivers exactly one target per row.
        row.setOnClickFillInIntent(ToolRow.TAP_TARGET, ToolRow.fillIn(cell, toolId, appWidgetId))
        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
