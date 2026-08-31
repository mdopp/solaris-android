package cloud.dopp.solaris.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.realtime.NoticeActions
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
 * 5. **A notification's action button** (#116/#123): the payload's own
 *    `{entity_id, service}` pair, run verbatim — asked first when the payload's
 *    `confirm` says so, through the very dialog in job 1 and not a second one.
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
        // A notification's action button (#123). It carries the server's own
        // `{entity_id, service}` pair and is run **verbatim**; what is decided
        // here is only whether to ask first.
        if (intent.getBooleanExtra(EXTRA_NOTICE_ACTION, false)) {
            runNoticeAction(entityId, service)
            return
        }
        if (entityId == null || service == null) {
            finish()
            return
        }
        showConfirm(entityId, service, appWidgetId)
    }

    /**
     * The confirm dialog for one service call — the single shape every action
     * dialog wears (#113): the question on a Solaris card, the yes as a full-width
     * row, Abbrechen underneath it.
     *
     * Wording follows the service's own direction (#85) — a substring test on
     * "close" mislabels `lock.lock` and `alarm_arm_*`, so [ConfirmVerb] maps the
     * dotted `domain.action` instead and the button follows the same verb.
     */
    private fun showConfirm(entityId: String, service: String, appWidgetId: Int) {
        val action = ConfirmVerb.of(service)
        val verb = getString(ConfirmWording.verbRes(action))
        ActionDialog.show(
            activity = this,
            title = getString(R.string.widget_confirm_title),
            message = getString(R.string.widget_confirm_msg, label(appWidgetId, entityId), verb),
            sheet = ActionSheets.confirm(action),
            onPick = { runService(entityId, service, appWidgetId) },
            onCancel = { finish() },
        )
    }

    /**
     * A notice's button (#116 boundary, #123 contract). The pair was validated on
     * the way in ([NoticeActions]) and is validated again **here**, from the
     * intent's own strings: a stale or forged button aimed at a lock or an alarm
     * panel must reach nothing, exactly as [EXTRA_SHORTCUT_TOGGLE] re-derives its
     * domain rather than trusting the sender (#100).
     *
     * `confirm` decides whether the resident is asked first, and its **default is
     * `true`**: an intent that lost the flag — an older build's `PendingIntent`,
     * a forgery — asks rather than acts. The unknown case is never the harmless
     * one. When the flag says no ask, the call still goes out unconfirmed, so the
     * server's authoritative `403 sensitive_action` gate stays in front of it and
     * simply lands in the very same dialog.
     *
     * Neither branch weakens the lock screen: this activity declares no
     * `showWhenLocked` and the notification's `PendingIntent` is flagged
     * `setAuthenticationRequired`, so the device lock is already behind us.
     */
    private fun runNoticeAction(entityId: String?, service: String?) {
        if (!NoticeActions.runnable(entityId, service)) {
            finish()
            return
        }
        val entity = entityId!!.trim()
        val svc = service!!.trim()
        if (intent.getBooleanExtra(EXTRA_NOTICE_CONFIRM, true)) {
            showConfirm(entity, svc, AppWidgetManager.INVALID_APPWIDGET_ID)
        } else {
            runUnconfirmed(entity, svc)
        }
    }

    /**
     * Run a notice's un-gated action and finish — or, if the server answers its
     * 403 confirm gate after all, fall into [showConfirm] rather than reporting a
     * failure. The server stays authoritative about what needs a yes; `confirm`
     * only spares the round trip when it already knows the answer.
     */
    private fun runUnconfirmed(entityId: String, service: String) {
        thread {
            var sensitive = false
            val ok = runCatching {
                try {
                    ApiClient(applicationContext).call(entityId, service)
                } catch (e: ApiClient.SensitiveException) {
                    sensitive = true
                    true // the server answered; the dialog is the next step
                }
            }.getOrDefault(false)
            if (!sensitive) {
                WidgetStore.noteEntityUsed(applicationContext, entityId)
                // A tap that went nowhere must not look like it worked (#111).
                if (!ok) WidgetActionReceiver.reportFailure(applicationContext, entityId)
            }
            runOnUiThread {
                if (sensitive) showConfirm(entityId, service, AppWidgetManager.INVALID_APPWIDGET_ID) else finish()
            }
        }
    }

    /**
     * The 1×1 lock tile's chooser (#92). The tile's tap no longer runs anything:
     * it lands here, and **this pick is the confirmation** — the user named the
     * direction in a dialog that carries the device's name, so the call goes out
     * `confirmed = true` and no second "wirklich?" is stacked on top of it. The
     * server's 403 gate is untouched; it is simply already satisfied.
     *
     * "Tür öffnen" is not an ordinary entry. It is the only one that opens the
     * door, so [ActionSheets.lock] puts it last in the stack, in the warning
     * colour and behind its own warning line, and it appears only when the lock
     * advertises a latch — see [LockChooser]. What it must never be is the row
     * where another dialog puts "Abbrechen" (#113); the footer is not a slot any
     * action can reach.
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
        val sheet = ActionSheets.lock(cached?.supportedFeatures)
        ActionDialog.show(
            activity = this,
            title = getString(R.string.widget_lock_title, label(appWidgetId, entityId)),
            message = null,
            sheet = sheet,
            onPick = { i ->
                sheet.items[i].service?.let { runService(entityId, it, appWidgetId) } ?: finish()
            },
            // Abbrechen does nothing at all — no call, no refresh.
            onCancel = { finish() },
        )
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
            val result = runCatching {
                ApiClient(applicationContext).call(entityId, service, confirmed = true)
            }
            // …and the same answer says whether the server is there at all: the
            // confirmed action is a measurement of the connection, taken while the
            // user watches. A hit clears an existing mark, a miss sets one now.
            ActionProbe.record(applicationContext, appWidgetId, ActionProbe.of(result))
            val ok = result.getOrDefault(false) // the tile keeps its last known state
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
        // The sheet is built in palette order, so the pick's index *is* the
        // palette's index — nothing here re-derives which colour was meant.
        ActionDialog.show(
            activity = this,
            title = getString(R.string.widget_color_title, WidgetStore.name(this, appWidgetId)),
            message = null,
            sheet = ActionSheets.colors(),
            onPick = { which -> runColor(entityId, appWidgetId, LightColors.PALETTE[which]) },
            onCancel = { finish() },
        )
    }

    /** Send one picked colour and refresh the tile, then finish. */
    private fun runColor(entityId: String, appWidgetId: Int, swatch: LightColors.Swatch) {
        val name = WidgetStore.name(this, appWidgetId)
        thread {
            val result = runCatching {
                ApiClient(applicationContext).call(
                    entityId, "light.turn_on", data = LightColors.data(swatch),
                )
            }
            ActionProbe.record(applicationContext, appWidgetId, ActionProbe.of(result))
            val ok = result.getOrDefault(false) // the tile keeps its last known colour
            // A colour that never arrived is still a tap that did nothing (#111).
            if (!ok) WidgetActionReceiver.reportFailure(applicationContext, name)
            DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
            runOnUiThread { finish() }
        }
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
         * Boolean extra: this intent came from a **notification's** action button
         * (#123). It carries `{entity_id, service}` verbatim from the payload and
         * is re-validated here against [NoticeActions] — never trusted.
         */
        const val EXTRA_NOTICE_ACTION = "notice_action"

        /**
         * Boolean extra: the payload's `confirm`. **Defaults to `true` when
         * absent** — an intent that lost the flag asks rather than acts.
         */
        const val EXTRA_NOTICE_CONFIRM = "notice_confirm"

        /**
         * String extra: the device's name as the shortcut published it (#100).
         * A catalogue device has no [WidgetStore] row to read a name from, and
         * this is only ever used for wording — never to decide anything.
         */
        const val EXTRA_NAME = "device_name"
    }
}
