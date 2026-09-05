package cloud.dopp.solaris.realtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cloud.dopp.solaris.R
import cloud.dopp.solaris.realtime.RealtimeProtocol.NoticeCategory
import cloud.dopp.solaris.realtime.RealtimeProtocol.NoticeEvent
import cloud.dopp.solaris.widget.ActionDialog
import cloud.dopp.solaris.widget.PwaLauncher
import cloud.dopp.solaris.widget.WidgetActionActivity
import cloud.dopp.solaris.widget.WidgetActionReceiver
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows a household notice ([RealtimeProtocol.EVENT_HA]) as a native
 * notification — the one place both the live stream ([RealtimeService]) and the
 * screen-off catch-up ([NoticeCatchUp]) go through, so the two paths cannot drift
 * apart in what they display or in what they let a button do.
 *
 * **Not an alarm channel.** Every category — timers and reminders included — is
 * best effort: immediate while the stream is up, otherwise delayed or lost. The
 * long version is at [RealtimeProtocol.EVENT_HA]; read it before hanging anything
 * safety-critical on a channel created here.
 *
 * **One channel per category** (#1280), alongside `solaris_realtime`,
 * `solaris_approvals` and `solaris_updates`: whoever mutes the house's notices
 * must not thereby lose a timer, and a reminder must stay mutable on its own.
 *
 * ## The lock-screen boundary
 *
 * A notification is reachable from the lock screen, so a button on one is a way
 * to act on the household without unlocking — unless it is built exactly like
 * this:
 *
 * - **The button is an Activity intent, never a broadcast.** A broadcast
 *   `PendingIntent` fires straight from the lock screen with no keyguard in
 *   between; an Activity one puts the device lock in front of it. It lands in
 *   [WidgetActionActivity] — the same screen the widget's confirm dialog and the
 *   app-icon shortcut use (#97/#100/#113), which re-validates the pair it was
 *   handed instead of trusting it, and keeps the server's authoritative
 *   `403 sensitive_action` gate in front of anything gated.
 * - **`setAuthenticationRequired`** on top of that (API 31+), so the platform
 *   demands the unlock itself rather than leaving it to launch semantics.
 * - **Nothing here declares `showWhenLocked` and nothing dismisses the keyguard.**
 *   [WidgetActionActivity] states the same rule for the widget path; this surface
 *   does not get to weaken it.
 * - **Which actions exist at all** is [NoticeActions]' decision — a lock or an
 *   alarm panel gets no button, however the payload words it.
 * - **A `confirm` action asks first** (#123), through that same dialog. That flag
 *   is *information from the server*, never a substitute for the three rules
 *   above: two independent locks, and one that relies on the other is none.
 */
object NoticeNotifier {

    /** Id space for notices, kept clear of 4801/49xx (service, approvals, updates). */
    private const val NOTIF_ID_BASE = 4930

    /** Slots per category, so consecutive notices stack instead of replacing. */
    private const val SLOTS = 16

    private val seq = AtomicInteger(0)

    /**
     * Post one notice. Best effort throughout: without POST_NOTIFICATIONS (API
     * 33+) nothing appears, and no failure here may reach the caller — a bad
     * frame or a missing permission must never take the stream down.
     */
    fun post(context: Context, ev: NoticeEvent, backlogId: String? = null) {
        val ctx = context.applicationContext
        // Remember it before it is shown, so the catch-up backlog (#124) can tell
        // a notice it already delivered from one it still owes — whichever of the
        // two paths showed it. A stream frame carries no id, so the fingerprint is
        // what makes the two comparable at all.
        NoticeSeen.mark(ctx, NoticeBacklog.keysOf(ev, backlogId))
        ensureChannel(ctx, ev.category)
        val id = NOTIF_ID_BASE + ev.category.ordinal * SLOTS + (seq.getAndIncrement() % SLOTS)
        val body = ev.body.ifBlank { ctx.getString(R.string.notice_generic) }
        val b = NotificationCompat.Builder(ctx, ev.category.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(NOTIF_ACCENT)
            .setContentTitle(ev.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            // Presentation only (#1280): urgency changes how a notice is shown,
            // never whether it arrives. Pre-O only — from O the channel decides.
            .setPriority(priorityOf(ev.urgency))
            // A tap opens Solaris; the contract carries no deep link for a notice.
            // The one exception is an app-update notice (#143), which opens the
            // download on the paired server instead — the notice is the TRIGGER,
            // never the source of the address (see NoticeEvent.kind).
            .setContentIntent(PwaLauncher.tapPending(ctx, id, tapRoute(ev)))
        NoticeActions.runnable(ev.actions).forEachIndexed { i, action ->
            b.addAction(button(ctx, id, i, action))
        }
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.notify(id, b.build())
        }
    }

    /**
     * One offered action, as the app is willing to run it: an **Activity**
     * `PendingIntent` into the existing shortcut trampoline, immutable, and
     * flagged as needing authentication. See the class comment for why each of
     * those three is load-bearing.
     */
    private fun button(
        ctx: Context,
        notifId: Int,
        index: Int,
        action: RealtimeProtocol.NoticeAction,
    ): NotificationCompat.Action {
        val intent = Intent(ctx, WidgetActionActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // The payload's own call, passed through verbatim (#123) — nothing
            // here derives a service from an entity's name.
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, NoticeActions.entityId(action))
            .putExtra(WidgetActionReceiver.EXTRA_SERVICE, NoticeActions.service(action))
            .putExtra(WidgetActionActivity.EXTRA_NOTICE_ACTION, true)
            .putExtra(WidgetActionActivity.EXTRA_NOTICE_CONFIRM, action.confirm)
            .addFlags(ActionDialog.TASK_FLAGS)
        val pi = PendingIntent.getActivity(
            ctx, notifId * RealtimeProtocol.MAX_NOTICE_ACTIONS + index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, action.title, pi)
            // The device lock stays in front of the household, on API 31+ by the
            // platform's own doing and not merely by how the intent is launched.
            .setAuthenticationRequired(true)
            .build()
    }

    /**
     * Where a tap goes (#143). Only the closed value
     * [RealtimeProtocol.KIND_APP_UPDATE] diverts it; anything else opens Solaris,
     * exactly as before.
     */
    internal fun tapRoute(ev: RealtimeProtocol.NoticeEvent): String =
        if (ev.kind == RealtimeProtocol.KIND_APP_UPDATE) PwaLauncher.Routes.DOWNLOAD
        else PwaLauncher.Routes.ROOT

    /** `low|normal|high` → the pre-O priority. Never an escalation, only a look. */
    private fun priorityOf(urgency: String): Int = when (urgency) {
        "low" -> NotificationCompat.PRIORITY_LOW
        "high" -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    /**
     * Create the category's channel if it is missing. DEFAULT importance for all
     * three — a notice may alert, but nothing here may present itself as urgent by
     * construction; raising or silencing one category is the user's call, and the
     * separate channels are what make that possible.
     */
    fun ensureChannel(context: Context, category: NoticeCategory) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctx = context.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(category.channelId) != null) return
        val ch = NotificationChannel(
            category.channelId,
            ctx.getString(nameRes(category)),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = ctx.getString(descRes(category)) }
        runCatching { nm.createNotificationChannel(ch) }
    }

    private fun nameRes(category: NoticeCategory): Int = when (category) {
        NoticeCategory.HOUSE -> R.string.notice_house_channel_name
        NoticeCategory.TIMER -> R.string.notice_timer_channel_name
        NoticeCategory.REMINDER -> R.string.notice_reminder_channel_name
    }

    private fun descRes(category: NoticeCategory): Int = when (category) {
        NoticeCategory.HOUSE -> R.string.notice_house_channel_desc
        NoticeCategory.TIMER -> R.string.notice_timer_channel_desc
        NoticeCategory.REMINDER -> R.string.notice_reminder_channel_desc
    }
}
