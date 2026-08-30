package cloud.dopp.solaris.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * What a tap on a tool widget row does (#70/#90/#107).
 *
 * A collection has exactly one `PendingIntentTemplate`, so every row tap arrives
 * here and the row's fill-in says what this particular row can offer. Three
 * attempts to give the row a *second* target — an action button beside the text —
 * lost the press on the device, the last one disprovably so, and Android's
 * documented multi-target-per-row support evidently isn't what this launcher
 * does. So the row has one target again and the choice moved into a **sheet**:
 * the pattern the lock chooser (#92) already proves here.
 *
 * - The row offers an action ([EXTRA_ACTION_ID]) → [ActionDialog] shows
 *   [ActionSheets.toolRow]; the pick runs it, or opens the item, or does nothing.
 * - It offers only the item route ([EXTRA_PATH]) → open it straight away. A tap
 *   that can merely navigate shouldn't have to be confirmed.
 * - It offers neither → the PWA root, exactly as before all of this.
 *
 * The three refusals are handled the way solarisbay#1214 spelled them out: a
 * `confirm_required` 403 opens the same kind of dialog [WidgetActionActivity]
 * uses and re-sends confirmed; a `forbidden` 403 (every admin action on `/napi/`)
 * and an `unknown_action` 404 fail **silently** — there is nothing the user could
 * confirm.
 *
 * Invisible unless it has something to ask: no layout is set, so a plain open
 * never flashes a screen. Only the sheets paint anything — [ActionDialog] draws
 * its own scrim then, so a dialog no longer shows whatever sat on top of the
 * app's task (#114).
 */
class ToolActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)?.trim()?.ifBlank { null }
        val itemPath = intent.getStringExtra(EXTRA_PATH)?.trim()?.ifBlank { null }
        val choices = ToolRow.choices(actionId != null, itemPath)
        if (!ToolRow.asksFirst(choices)) {
            // Nothing to decide: open the item's card, or the root for a row whose
            // tool declares neither an action nor an id field (#107).
            PwaLauncher.open(this, itemPath ?: PwaLauncher.Routes.ROOT)
            finish()
            return
        }
        offer(choices, actionId!!, itemPath)
    }

    /**
     * The row's sheet (#90) — one entry per thing this row can do, Abbrechen in
     * the same place as in every other dialog, and a pick that runs exactly one of
     * them. The title names the row, because a catalog action id says nothing a
     * user would recognise.
     */
    private fun offer(choices: List<ToolRowChoice>, actionId: String, itemPath: String?) {
        ActionDialog.show(
            activity = this,
            title = rowTitle(),
            message = null,
            sheet = ActionSheets.toolRow(choices),
            onPick = { i ->
                when (choices[i]) {
                    ToolRowChoice.RUN -> run(actionId, params(), confirmed = false)
                    ToolRowChoice.OPEN -> {
                        PwaLauncher.open(this, itemPath ?: PwaLauncher.Routes.ROOT)
                        finish()
                    }
                }
            },
            onCancel = { finish() },
        )
    }

    /** The row's own title, or the widget's name when the row carried none. */
    private fun rowTitle(): CharSequence =
        intent.getStringExtra(EXTRA_TITLE)?.trim()?.ifBlank { null }
            ?: getString(R.string.tool_widget_label)

    /** The resolved `params` object, or an empty one if it didn't survive the trip. */
    private fun params(): JSONObject = try {
        JSONObject(intent.getStringExtra(EXTRA_PARAMS).orEmpty().ifBlank { "{}" })
    } catch (e: Exception) {
        JSONObject()
    }

    private fun run(actionId: String, params: JSONObject, confirmed: Boolean) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        thread {
            val outcome = try {
                ApiClient(applicationContext).postActionCallback(actionId, params, confirmed)
            } catch (e: Exception) {
                ApiClient.ActionOutcome.FAILED
            }
            runOnUiThread {
                if (outcome == ApiClient.ActionOutcome.CONFIRM_REQUIRED && !confirmed) {
                    confirm(actionId, params, appWidgetId)
                    return@runOnUiThread
                }
                // Only a run that actually happened is worth a refetch; a refused
                // one leaves the rows exactly as they are.
                if (outcome == ApiClient.ActionOutcome.OK &&
                    appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                ) {
                    ToolWidgetProvider.requestRefresh(applicationContext, appWidgetId)
                }
                finish()
            }
        }
    }

    /**
     * The server's confirm gate, surfaced. The wording names the row (the widget
     * knows no verb for a catalog action id, and inventing one would claim a
     * direction the def never stated).
     */
    private fun confirm(actionId: String, params: JSONObject, appWidgetId: Int) {
        // The same sheet the device widget uses (#113) — one shape for every
        // action dialog, Abbrechen always the footer.
        ActionDialog.show(
            activity = this,
            title = getString(R.string.widget_confirm_title),
            message = getString(R.string.tool_action_confirm, rowTitle()),
            sheet = ActionSheets.toolConfirm(),
            onPick = { run(actionId, params, confirmed = true) },
            onCancel = { finish() },
        )
    }

    companion object {
        /**
         * The item's own PWA route (#107), when the tool declared which field
         * carries an id. Absent ⇒ this row has no item route, and a tap with
         * nothing else to offer lands on the PWA root.
         */
        const val EXTRA_PATH = "path"

        /** The action the tool's def declared for this row. Absent ⇒ none. */
        const val EXTRA_ACTION_ID = "action_id"

        /** [EXTRA_ACTION_ID]'s resolved `params` object, JSON-encoded. */
        const val EXTRA_PARAMS = "params"

        /** The row's title — the wording of both the sheet and the confirm. */
        const val EXTRA_TITLE = "title"
    }
}
