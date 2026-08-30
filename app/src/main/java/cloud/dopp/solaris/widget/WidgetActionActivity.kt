package cloud.dopp.solaris.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
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
 *    reaction to a call, but the thing that decides *which* call is made. The
 *    app-icon shortcut of a lock (#97) opens exactly this, never a call.
 * 4. **Shortcut trampoline** (#97): an app-icon shortcut can only start an
 *    activity, so a non-sensitive device's shortcut lands here and is handed
 *    straight to [WidgetActionReceiver] as the widget's own toggle.
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
        // App-icon shortcut tap (#97). A shortcut cannot start a BroadcastReceiver,
        // so this invisible screen hands the tap to the same tap handler the widget
        // uses — the same op, the same 403 confirm path, no second code path to
        // keep safe.
        //
        // The domain is **never taken from the intent**: with a widget it is read
        // back from the store, and without one (#100) it is the entity id's own
        // prefix, i.e. the very string the call would be made against. Either way a
        // stale or forged shortcut aimed at a lock does nothing at all here.
        if (intent.getBooleanExtra(EXTRA_SHORTCUT_TOGGLE, false)) {
            if (hasWidget(appWidgetId)) {
                if (DeviceShortcuts.actsDirectly(WidgetStore.domain(this, appWidgetId))) {
                    sendBroadcast(tap(appWidgetId, null))
                }
            } else if (entityId != null && DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf(entityId))) {
                sendBroadcast(tap(appWidgetId, entityId))
            }
            finish()
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
            .setMessage(getString(R.string.widget_confirm_msg, label(appWidgetId, entityId), verb))
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
        // Without a widget there is no render cache to read the latch bit from, so
        // LockChooser sees null capabilities and offers no "Tür öffnen" — the
        // conservative direction, and the one #100 must not quietly reverse.
        val cached = if (hasWidget(appWidgetId)) WidgetCache.getCard(this, appWidgetId) else null
        val entries = LockChooser.entries(cached?.supportedFeatures)
        val rows = entries.filter { it != LockChoice.OPEN }
        val labels = rows.map { getString(it.labelRes) }.toTypedArray()
        val builder = AlertDialog.Builder(this, R.style.Theme_Solaris_AlertDialog)
            .setTitle(getString(R.string.widget_lock_title, label(appWidgetId, entityId)))
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
        val name = label(appWidgetId, entityId)
        thread {
            // The call's own answer decides (#111): `call` returns false on a
            // non-2xx — the outage's 500s came back this way — so a confirmed
            // "Abschließen" that the server refused must not end in silence.
            val ok = try {
                ApiClient(applicationContext).call(entityId, service, confirmed = true)
            } catch (e: Exception) {
                false // the tile keeps its last known state; the user hears about it
            }
            if (!ok) WidgetActionReceiver.reportFailure(applicationContext, name)
            // The device was used, whatever the call's outcome — that is what the
            // app-icon menu orders by (#100).
            WidgetStore.noteEntityUsed(applicationContext, entityId)
            // Nothing to re-render when the action came from a catalogue shortcut.
            if (hasWidget(appWidgetId)) {
                DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
            }
            runOnUiThread { finish() }
        }
    }

    private fun hasWidget(appWidgetId: Int) =
        appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    /**
     * What to call the device in a dialog: the widget's stored name, else the
     * label the shortcut carried, else the entity id. A device without a widget
     * has no store row, and a nameless "Wirklich …?" is worse than a technical
     * one.
     */
    private fun label(appWidgetId: Int, entityId: String?): String =
        WidgetStore.name(this, appWidgetId)
            .ifBlank { intent.getStringExtra(EXTRA_NAME).orEmpty() }
            .ifBlank { entityId.orEmpty() }

    /** The widget's own tap, as a broadcast — [entityId] only when no widget backs it. */
    private fun tap(appWidgetId: Int, entityId: String?): Intent =
        Intent(this, WidgetActionReceiver::class.java)
            .setAction(WidgetActionReceiver.ACTION_TAP)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(WidgetActionReceiver.EXTRA_OP, WidgetActionReceiver.OP_TOGGLE)
            .apply { entityId?.let { putExtra(WidgetActionReceiver.EXTRA_ENTITY, it) } }

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
                val name = WidgetStore.name(this, appWidgetId)
                thread {
                    val ok = try {
                        ApiClient(applicationContext).call(
                            entityId, "light.turn_on", data = LightColors.data(swatch),
                        )
                    } catch (e: Exception) {
                        false // the tile keeps its last known colour
                    }
                    // A colour that never arrived is still a tap that did nothing (#111).
                    if (!ok) WidgetActionReceiver.reportFailure(applicationContext, name)
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

        /**
         * Boolean extra: an app-icon shortcut (#97) wants the bound widget's
         * ordinary toggle. Only honoured for a domain [DeviceShortcuts] lets act
         * directly — never for a lock.
         */
        const val EXTRA_SHORTCUT_TOGGLE = "shortcut_toggle"

        /**
         * String extra: the device's name as the shortcut published it (#100).
         * A catalogue device has no [WidgetStore] row to read a name from, and
         * this is only ever used for wording — never to decide anything.
         */
        const val EXTRA_NAME = "device_name"
    }
}
