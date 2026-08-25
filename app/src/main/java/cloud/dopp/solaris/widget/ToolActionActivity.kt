package cloud.dopp.solaris.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The tool widget's row target (#70/#90). It is one activity for **both** row taps
 * because a collection has exactly one `PendingIntentTemplate`: every fill-in in
 * `item_tool_row.xml` merges into the same base intent, so the two behaviours have
 * to be told apart by extras here rather than by two components.
 *
 * - No [EXTRA_ACTION_ID] → the row body was tapped: open the PWA at [EXTRA_PATH],
 *   exactly as before this ticket.
 * - With one → the action button was tapped: `POST /napi/action-callback` with the
 *   params the catalog declared and the row already resolved ([ToolCells]).
 *
 * The three refusals are handled the way solarisbay#1214 spelled them out: a
 * `confirm_required` 403 opens the same kind of dialog [WidgetActionActivity] uses
 * and re-sends confirmed; a `forbidden` 403 (every admin action on `/napi/`) and an
 * `unknown_action` 404 fail **silently** — there is nothing the user could confirm.
 *
 * Invisible: no layout is set, so a plain row tap never flashes a screen.
 */
class ToolActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
        if (actionId.isNullOrBlank()) {
            PwaLauncher.open(
                this, intent.getStringExtra(EXTRA_PATH) ?: PwaLauncher.Routes.ROOT,
            )
            finish()
            return
        }
        run(actionId, params(), confirmed = false)
    }

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
        val title = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null }
            ?: getString(R.string.tool_widget_label)
        AlertDialog.Builder(this, R.style.Theme_Solaris_AlertDialog)
            .setTitle(R.string.widget_confirm_title)
            .setMessage(getString(R.string.tool_action_confirm, title))
            .setPositiveButton(R.string.tool_action_do) { _, _ ->
                run(actionId, params, confirmed = true)
            }
            .setNegativeButton(R.string.widget_confirm_no) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        /** Row-body tap: the PWA route to open. */
        const val EXTRA_PATH = "path"

        /** Button tap: the declared action id. Absent ⇒ this is a body tap. */
        const val EXTRA_ACTION_ID = "action_id"

        /** Button tap: the resolved `params` object, JSON-encoded. */
        const val EXTRA_PARAMS = "params"

        /** Button tap: the row's title, for the confirm dialog's wording. */
        const val EXTRA_TITLE = "title"
    }
}
