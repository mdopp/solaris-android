package cloud.dopp.solaris

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cloud.dopp.solaris.realtime.NoticeActions
import cloud.dopp.solaris.realtime.NoticeNotifier
import cloud.dopp.solaris.realtime.RealtimeProtocol
import cloud.dopp.solaris.realtime.RealtimeProtocol.NoticeAction
import cloud.dopp.solaris.realtime.RealtimeProtocol.NoticeCategory
import cloud.dopp.solaris.widget.WidgetActionActivity
import cloud.dopp.solaris.widget.WidgetActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * The `ha` notice frame (#116) under the contract of #123 — parse, channel, and
 * the one thing that actually needed a review before it shipped: **what a button
 * on a notification can reach**.
 *
 * A notification lies on the lock screen, so a button on one is a way to act on
 * the household without unlocking unless every step of the path says otherwise.
 * The assertions below therefore run in both directions: what a notice *may* run
 * (a light, a switch, a cover, a climate — the server's own actionable set,
 * behind the device lock), and what it may *not* — a lock or an alarm panel gets
 * no button at all however the payload words it, a forged intent carrying one
 * reaches nothing, and a **missing `confirm` is read as `true`** so an older
 * server can never produce a one-tap garage door.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeEventTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    // ---- the frame -------------------------------------------------------

    @Test fun parsesAFullNoticeFrame() {
        val ev = RealtimeProtocol.parseHa(
            """
            {"kind":"ha","target":"michael","title":"Waschmaschine fertig",
             "body":"Seit 5 Minuten","urgency":"low","category":"house",
             "actions":[{"entity_id":"light.keller","service":"light.toggle",
                         "title":"Licht an","confirm":false}]}
            """.trimIndent(),
        )!!
        assertEquals("Waschmaschine fertig", ev.title)
        assertEquals("Seit 5 Minuten", ev.body)
        assertEquals(NoticeCategory.HOUSE, ev.category)
        assertEquals("low", ev.urgency)
        assertEquals(
            listOf(NoticeAction("light.keller", "light.toggle", "Licht an", false)),
            ev.actions,
        )
    }

    /** `category` is optional and additive — a v0.46.0 frame is still a notice. */
    @Test fun aFrameWithoutCategoryIsAHouseNotice() {
        val ev = RealtimeProtocol.parseHa("""{"kind":"ha","title":"Post da"}""")!!
        assertEquals(NoticeCategory.HOUSE, ev.category)
        assertEquals("normal", ev.urgency)
        assertTrue(ev.actions.isEmpty())
    }

    /** An unknown future category lands on the house channel — never dropped. */
    @Test fun anUnknownCategoryStillArrives() {
        val ev = RealtimeProtocol.parseHa("""{"title":"X","category":"doorbell"}""")!!
        assertEquals(NoticeCategory.HOUSE, ev.category)
        assertEquals(NoticeCategory.TIMER, NoticeCategory.of("timer"))
        assertEquals(NoticeCategory.REMINDER, NoticeCategory.of("REMINDER"))
        assertEquals(NoticeCategory.HOUSE, NoticeCategory.of(null))
    }

    /** A malformed frame means "nothing to report", exactly like `servicebay`. */
    @Test fun malformedFramesReportNothing() {
        assertNull(RealtimeProtocol.parseHa(null))
        assertNull(RealtimeProtocol.parseHa(""))
        assertNull(RealtimeProtocol.parseHa("not json"))
        assertNull(RealtimeProtocol.parseHa("[1,2,3]"))
        assertNull(RealtimeProtocol.parseHa("""{"body":"kein Titel"}"""))
        assertNull(RealtimeProtocol.parseHa("""{"title":"   "}"""))
    }

    /**
     * **The single most important line of #123.** `confirm` is computed by the
     * server and is always on the wire from v0.48.0 — so its *absence* means an
     * older server that never weighed the question up. Reading that as `false`
     * would eventually put a garage door one tap away on a lock screen. Same rule
     * as #84 (`unknown` never looks like `abgeschlossen`) and #120 (a missing card
     * was painted "lamp off"): the unknown case is never the harmless one.
     */
    @Test fun aMissingConfirmIsReadAsTrue() {
        fun confirmOf(json: String): Boolean =
            RealtimeProtocol.parseHa("""{"title":"T","actions":[$json]}""")!!.actions.single().confirm

        val garage = """"entity_id":"cover.garagentor","service":"cover.open_cover","title":"Auf""""
        // No field at all — an older server. Ask.
        assertTrue(confirmOf("{$garage}"))
        // Present but null, or present but not a boolean — still unknown. Ask.
        assertTrue(confirmOf("{$garage,\"confirm\":null}"))
        assertTrue(confirmOf("{$garage,\"confirm\":\"vielleicht\"}"))
        // Stated. Believed, in both directions.
        assertTrue(confirmOf("{$garage,\"confirm\":true}"))
        assertFalse(confirmOf("{$garage,\"confirm\":false}"))
    }

    /** A bad button must not cost the notice its text. */
    @Test fun actionsAreCappedAndCleaned() {
        val ev = RealtimeProtocol.parseHa(
            """
            {"title":"T","actions":[
              {"entity_id":"light.a","service":"light.toggle","title":"A"},
              {"entity_id":"","service":"light.toggle","title":"leer"},
              {"entity_id":"light.b","service":"light.toggle"},
              {"entity_id":"light.x","title":"kein Dienst"},
              "kaputt",
              {"action":"light.alt","title":"alte Form"},
              {"entity_id":"light.c","service":"light.toggle","title":"C"},
              {"entity_id":"light.d","service":"light.toggle","title":"D"},
              {"entity_id":"light.e","service":"light.toggle","title":"E"}]}
            """.trimIndent(),
        )!!
        assertEquals(
            listOf("light.a", "light.c", "light.d"),
            ev.actions.map { it.entityId },
        )
    }

    /**
     * The old one-string form (`{action,title}`) is refused by the server with a
     * 400 since v0.48.0, but an older box may still emit it — and then the notice
     * must still *arrive*, only without a button. A swallowed notice is the worse
     * failure.
     */
    @Test fun anOldFormActionCostsTheButtonAndNotTheNotice() {
        val ev = RealtimeProtocol.parseHa(
            """{"title":"Fenster offen","actions":[{"action":"cover.close","title":"Zu"}]}""",
        )!!
        assertEquals("Fenster offen", ev.title)
        assertTrue(ev.actions.isEmpty())
        assertEquals(0, post(ev).actions?.size ?: 0)
    }

    // ---- what a notice may run ------------------------------------------

    @Test fun onlyAnActionableDomainGetsAButton() {
        assertTrue(NoticeActions.runnable(action("light.keller", "light.toggle")))
        assertTrue(NoticeActions.runnable(action("switch.pumpe", "switch.turn_on")))
        // New since #123: cover and climate are server-side actionable.
        assertTrue(NoticeActions.runnable(action("cover.garagentor", "cover.close_cover")))
        assertTrue(NoticeActions.runnable(action("climate.bad", "climate.set_hvac_mode")))
        // Unrepresentable, in the payload and here — this is the third refusal.
        assertFalse(NoticeActions.runnable(action("lock.front_door", "lock.unlock")))
        assertFalse(NoticeActions.runnable(action("lock.haustuer", "lock.open")))
        assertFalse(NoticeActions.runnable(action("alarm_control_panel.haus", "alarm_control_panel.alarm_disarm")))
        assertFalse("no lock may creep into the set", "lock" in NoticeActions.ACTIONABLE_DOMAINS)
        assertFalse("nor an alarm panel", "alarm_control_panel" in NoticeActions.ACTIONABLE_DOMAINS)
        // The service's domain must equal the entity's — no translation job.
        assertFalse(NoticeActions.runnable(action("cover.garagentor", "light.toggle")))
        assertFalse(NoticeActions.runnable(action("light.keller", "lock.open")))
        // Anything that is not a dotted pair at all.
        assertFalse(NoticeActions.runnable(action("türöffnen", "light.toggle")))
        assertFalse(NoticeActions.runnable(action("light.a.b", "light.toggle")))
        assertFalse(NoticeActions.runnable(action("light.", "light.toggle")))
        assertFalse(NoticeActions.runnable(action(".keller", "light.toggle")))
        assertFalse(NoticeActions.runnable(action("light keller", "light.toggle")))
        assertFalse(NoticeActions.runnable(action("light.keller", "light")))
        assertFalse(NoticeActions.runnable(action("light.keller", "")))
    }

    // ---- the notification ------------------------------------------------

    @Test fun theCategoryDecidesTheChannel() {
        val posted = NoticeCategory.entries.map { cat ->
            post(RealtimeProtocol.NoticeEvent("T", "B", cat, "normal", emptyList()))
        }
        assertEquals(
            listOf("solaris_notice_house", "solaris_notice_timer", "solaris_notice_reminder"),
            posted.map { it.channelId },
        )
        // Separately mutable means separately existing — three channels, and none
        // of them the approvals/updates/live ones.
        val ids = shadowOf(nm()).notificationChannels.map { (it as android.app.NotificationChannel).id }
        assertTrue(ids.containsAll(NoticeCategory.entries.map { it.channelId }))
        assertFalse(ids.contains("solaris_approvals"))
        // Three posts, three notifications: consecutive notices stack rather than
        // replace each other (post() asserts the count rises every time).
        assertEquals(3, posted.size)
    }

    @Test fun aLockNoticeCarriesNoButton() {
        val n = post(
            RealtimeProtocol.NoticeEvent(
                "Haustür offen", "seit 10 Min", NoticeCategory.HOUSE, "high",
                listOf(
                    action("lock.haustuer", "lock.lock", "Abschließen"),
                    action("alarm_control_panel.haus", "alarm_control_panel.alarm_arm_away", "Scharf"),
                ),
            ),
        )
        assertEquals(0, n.actions?.size ?: 0)
        assertEquals("Haustür offen", n.extras.getString(Notification.EXTRA_TITLE))
    }

    /**
     * The one wired action: an **Activity** intent into the existing action screen
     * — not a broadcast, which would fire straight off the lock screen — flagged
     * as needing authentication on top of that, and carrying the payload's own
     * `{entity_id, service}` pair verbatim (#123).
     */
    @Test fun aNoticeButtonCarriesTheServersOwnCall() {
        val n = post(
            RealtimeProtocol.NoticeEvent(
                "Garagentor offen", "", NoticeCategory.HOUSE, "normal",
                listOf(action("cover.garagentor", "cover.close_cover", "Schließen", confirm = true)),
            ),
        )
        assertEquals(1, n.actions.size)
        assertTrue(NotificationCompat.getAction(n, 0)!!.isAuthenticationRequired)
        val pi = shadowOf(n.actions[0].actionIntent)
        assertTrue("a notice button must launch an activity, never a broadcast", pi.isActivityIntent)
        assertFalse(pi.isBroadcastIntent)
        val intent = pi.savedIntent
        assertEquals(WidgetActionActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(WidgetActionActivity.EXTRA_NOTICE_ACTION, false))
        assertTrue(intent.getBooleanExtra(WidgetActionActivity.EXTRA_NOTICE_CONFIRM, false))
        // Passed through unchanged — nothing derived a service from a name.
        assertEquals("cover.garagentor", intent.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        assertEquals("cover.close_cover", intent.getStringExtra(WidgetActionReceiver.EXTRA_SERVICE))
        // …and the button never becomes the widget's own toggle any more.
        assertFalse(intent.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
    }

    /** `confirm:false` rides the same intent, only without the ask. */
    @Test fun anUnconfirmedActionCarriesTheFlagItWasGiven() {
        val n = post(
            RealtimeProtocol.NoticeEvent(
                "Waschmaschine fertig", "", NoticeCategory.HOUSE, "normal",
                listOf(action("light.keller", "light.toggle", "Licht an", confirm = false)),
            ),
        )
        val intent = shadowOf(n.actions[0].actionIntent).savedIntent
        assertFalse(intent.getBooleanExtra(WidgetActionActivity.EXTRA_NOTICE_CONFIRM, true))
    }

    // ---- the confirmation step -------------------------------------------

    /** A `confirm` action asks first — through the #113 dialog, not a second one. */
    @Test fun aConfirmActionAsksBeforeItActs() {
        launch(noticeIntent("cover.garagentor", "cover.close_cover", confirm = true))
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("the garage door must ask first", dialog != null && dialog.isShowing)
    }

    /** …and one the server cleared does not stop to ask. */
    @Test fun anUnconfirmedActionRunsWithoutADialog() {
        launch(noticeIntent("light.keller", "light.toggle", confirm = false))
        assertNull("a light must not stack a needless question", ShadowDialog.getLatestDialog())
    }

    /**
     * **A lost `confirm` asks.** A stale `PendingIntent` from an older build, or a
     * forgery, carries no flag — and the activity must then take the cautious
     * branch, never the harmless-looking one.
     */
    @Test fun anIntentWithoutTheFlagStillAsks() {
        val intent = noticeIntent("cover.garagentor", "cover.close_cover", confirm = true)
        intent.removeExtra(WidgetActionActivity.EXTRA_NOTICE_CONFIRM)
        launch(intent)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("no flag means ask", dialog != null && dialog.isShowing)
    }

    /**
     * The second half of the same guarantee: even a forged button aimed at a lock
     * — one this app would never build — runs nothing and asks nothing, because
     * the activity re-validates the pair it was handed instead of trusting it.
     */
    @Test fun aForgedLockButtonReachesNothing() {
        for (pair in listOf(
            "lock.haustuer" to "lock.open",
            "alarm_control_panel.haus" to "alarm_control_panel.alarm_disarm",
            "cover.garagentor" to "lock.open",
        )) {
            val activity = launch(noticeIntent(pair.first, pair.second, confirm = false))
            assertNull("${pair.first}/${pair.second} must reach nothing", ShadowDialog.getLatestDialog())
            assertTrue("${pair.first}/${pair.second} must be dropped", activity.isFinishing)
            assertTrue(broadcasts().isEmpty())
        }
    }

    // ---- the lock screen boundary itself ---------------------------------

    /**
     * Nothing on this path may show over the keyguard or dismiss it — the rule
     * [WidgetActionActivity] states for the widget path, asserted over the sources
     * so a later "make the button one tap faster" cannot quietly reverse it.
     */
    @Test fun nothingOnThisPathShowsOverTheLockScreen() {
        val forbidden = listOf(
            "showWhenLocked",
            "setShowWhenLocked",
            "requestDismissKeyguard",
            "FLAG_DISMISS_KEYGUARD",
            "FLAG_SHOW_WHEN_LOCKED",
            "setTurnScreenOn",
        )
        val offenders = mainSources().flatMap { src ->
            src.readLines().withIndex()
                .filter { (_, line) -> forbidden.any { line.contains(it) } }
                // The prose that forbids it is not a violation of it.
                .filterNot { (_, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("//") }
                .map { (i, line) -> "${src.name}:${i + 1} ${line.trim()}" }
        }
        assertTrue("nothing may bypass the device lock: $offenders", offenders.isEmpty())
        val manifest = File(mainDir(), "AndroidManifest.xml").readText()
        assertFalse("no activity may show over the keyguard", manifest.contains("showWhenLocked"))
    }

    /** A notice button is never a broadcast — that is what keeps the lock in front. */
    @Test fun theNotifierBuildsNoBroadcastIntent() {
        val src = File(mainJava(), "cloud/dopp/solaris/realtime/NoticeNotifier.kt").readText()
        assertFalse(src.contains("getBroadcast"))
        assertTrue(src.contains("setAuthenticationRequired(true)"))
    }

    // ---- helpers ---------------------------------------------------------

    private fun nm(): NotificationManager =
        ctx.getSystemService(NotificationManager::class.java)

    /**
     * Post one notice and hand back the notification the system actually got —
     * found by identity, not by position: the shadow manager's order is its own
     * business and a test must not quietly depend on it.
     */
    private fun post(ev: RealtimeProtocol.NoticeEvent): Notification {
        val before = shadowOf(nm()).allNotifications.toList()
        NoticeNotifier.post(ctx, ev)
        val all = shadowOf(nm()).allNotifications
        assertEquals("expected exactly one new notification", before.size + 1, all.size)
        return all.first { fresh -> before.none { it === fresh } }
    }

    /** One offered action, as the #123 contract shapes it. */
    private fun action(
        entityId: String,
        service: String,
        title: String = "Tu was",
        confirm: Boolean = true,
    ) = NoticeAction(entityId, service, title, confirm)

    /** The intent a notice button carries, built exactly as [NoticeNotifier] does. */
    private fun noticeIntent(entityId: String, service: String, confirm: Boolean): Intent =
        Intent(ctx, WidgetActionActivity::class.java)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, entityId)
            .putExtra(WidgetActionReceiver.EXTRA_SERVICE, service)
            .putExtra(WidgetActionActivity.EXTRA_NOTICE_ACTION, true)
            .putExtra(WidgetActionActivity.EXTRA_NOTICE_CONFIRM, confirm)

    /** Run the action screen for [intent], with a clean broadcast log. */
    private fun launch(intent: Intent): WidgetActionActivity {
        shadowOf(RuntimeEnvironment.getApplication()).clearBroadcastIntents()
        return Robolectric.buildActivity(WidgetActionActivity::class.java, intent).setup().get()
    }

    /** Every widget tap broadcast the last [launch] produced — expected: none. */
    private fun broadcasts(): List<Intent> =
        shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            .filter { it.action == WidgetActionReceiver.ACTION_TAP }

    /** Unit tests run from the module dir, but don't depend on it (as #88's guard). */
    private fun mainDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src/main"), File(dir, "app/src/main"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate src/main from ${File("").absolutePath}")
    }

    private fun mainJava(): File = File(mainDir(), "java")

    private fun mainSources(): List<File> =
        mainJava().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
