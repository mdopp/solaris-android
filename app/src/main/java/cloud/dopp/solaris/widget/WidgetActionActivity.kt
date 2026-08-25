package cloud.dopp.solaris.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import kotlin.concurrent.thread

/**
 * The device widget's one visible screen. Two jobs, both of them things a
 * `RemoteViews` cannot do itself:
 *
 * 1. **Confirm dialog** for a sensitive action (garage/door/gate, lock, alarm
 *    panel). Launched by the headless [WidgetActionReceiver] only when the
 *    server returns the 403 confirm gate — so ordinary toggles never spawn a
 *    visible screen. Whether a confirm is needed stays server-authoritative;
 *    only the wording is decided here, via [ConfirmVerb].
 * 2. **Colour palette** for a colour-capable light (#87), opened straight from
 *    the tile's swatch button. See [LightColors] for why a palette and not a
 *    picker.
 */
class WidgetActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val entityId = intent.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY)
        // Colour mode (#87) carries no service — the picked swatch names it.
        if (intent.getBooleanExtra(EXTRA_PICK_COLOR, false)) {
            if (entityId == null) finish() else showColorPalette(entityId, appWidgetId)
            return
        }
        val service = intent.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE)
        if (entityId == null || service == null) {
            finish()
            return
        }
        // Wording follows the service's own direction (#85) — a substring test on
        // "close" mislabels lock.lock and alarm_arm_*, so ConfirmVerb maps the
        // dotted domain.action instead and the button follows the same verb.
        val action = ConfirmVerb.of(service)
        val verb = getString(ConfirmWording.verbRes(action))
        val positive = ConfirmWording.positiveRes(action)
        AlertDialog.Builder(this, R.style.Theme_Solaris_AlertDialog)
            .setTitle(R.string.widget_confirm_title)
            .setMessage(getString(R.string.widget_confirm_msg, WidgetStore.name(this, appWidgetId), verb))
            .setPositiveButton(positive) { _, _ ->
                thread {
                    try {
                        ApiClient(applicationContext).call(entityId, service, confirmed = true)
                    } catch (e: Exception) {
                        // ignore
                    }
                    DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
                    runOnUiThread { finish() }
                }
            }
            .setNegativeButton(R.string.widget_confirm_no) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * The named-colour list for a colour-capable light (#87). Picking one sends
     * `light.turn_on` with `{"rgb_color":[r,g,b]}` — the same call the web card's
     * picker makes — and refreshes the tile so its swatch takes the new colour.
     * Colour is not a sensitive action, so there is no confirm step here.
     */
    private fun showColorPalette(entityId: String, appWidgetId: Int) {
        val labels = LightColors.PALETTE.map { getString(it.labelRes) }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_Solaris_AlertDialog)
            .setTitle(getString(R.string.widget_color_title, WidgetStore.name(this, appWidgetId)))
            .setItems(labels) { _, which ->
                val swatch = LightColors.PALETTE[which]
                thread {
                    try {
                        ApiClient(applicationContext).call(
                            entityId, "light.turn_on", data = LightColors.data(swatch),
                        )
                    } catch (e: Exception) {
                        // ignore — the tile keeps its last known colour
                    }
                    DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
                    runOnUiThread { finish() }
                }
            }
            .setNegativeButton(R.string.widget_confirm_no) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        /** Boolean extra: open the colour palette instead of a confirm (#87). */
        const val EXTRA_PICK_COLOR = "pick_color"
    }
}
