package cloud.dopp.solaris.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import kotlin.concurrent.thread

/**
 * The device widget's one visible screen. Three jobs, all of them things a
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
 * 3. **Lock chooser** for the 1×1 lock tile (#92) — the one dialog that is not a
 *    reaction to a call, but the thing that decides *which* call is made.
 *
 * The activity deliberately declares **no `showWhenLocked`** and dismisses no
 * keyguard: sitting behind the device lock is what makes a homescreen tile an
 * acceptable place for a front-door control at all.
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
        // Lock chooser (#92) carries no service either — the pick names it.
        if (intent.getBooleanExtra(EXTRA_LOCK_CHOOSE, false)) {
            if (entityId == null) finish() else showLockChooser(entityId, appWidgetId)
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
            .setPositiveButton(positive) { _, _ -> runService(entityId, service, appWidgetId) }
            .setNegativeButton(R.string.widget_confirm_no) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * The 1×1 lock tile's chooser (#92). The tile's tap no longer runs anything:
     * it lands here, and **this pick is the confirmation** — the user named the
     * direction in a dialog that carries the device's name, so the call goes out
     * `confirmed = true` and no second "wirklich?" is stacked on top of it. The
     * server's 403 gate is untouched; it is simply already satisfied.
     *
     * "Tür öffnen" is not a third list row. It is the only entry that opens the
     * door, so it sits apart in the button bar, in the warning colour, and only
     * appears when the lock advertises a latch — see [LockChooser].
     *
     * The capabilities come from the render cache, not the network: the dialog
     * must appear at once on a tap, and an offline phone must not silently lose
     * or gain the door-opening entry.
     */
    private fun showLockChooser(entityId: String, appWidgetId: Int) {
        val entries = LockChooser.entries(WidgetCache.getCard(this, appWidgetId)?.supportedFeatures)
        val rows = entries.filter { it != LockChoice.OPEN }
        val labels = rows.map { getString(it.labelRes) }.toTypedArray()
        val builder = AlertDialog.Builder(this, R.style.Theme_Solaris_AlertDialog)
            .setTitle(getString(R.string.widget_lock_title, WidgetStore.name(this, appWidgetId)))
            .setItems(labels) { _, which -> runService(entityId, rows[which].service, appWidgetId) }
            // Abbrechen does nothing at all — no call, no refresh.
            .setNegativeButton(R.string.widget_confirm_no) { _, _ -> finish() }
            .setOnCancelListener { finish() }
        if (LockChoice.OPEN in entries) {
            builder.setNeutralButton(R.string.widget_confirm_unlatch) { _, _ ->
                runService(entityId, LockChoice.OPEN.service, appWidgetId)
            }
        }
        val dialog = builder.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(getColor(R.color.solaris_warn))
    }

    /**
     * Run one service for the widget's entity and refresh the tile, then finish.
     * Always `confirmed = true`: every caller here got an explicit yes from the
     * user first (the confirm button, or the chooser's pick).
     */
    private fun runService(entityId: String, service: String, appWidgetId: Int) {
        thread {
            try {
                ApiClient(applicationContext).call(entityId, service, confirmed = true)
            } catch (e: Exception) {
                // ignore — the tile keeps its last known state
            }
            DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
            runOnUiThread { finish() }
        }
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

        /** Boolean extra: open the lock chooser instead of a confirm (#92). */
        const val EXTRA_LOCK_CHOOSE = "lock_choose"
    }
}
