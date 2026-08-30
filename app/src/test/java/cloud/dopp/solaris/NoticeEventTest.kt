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
import java.io.File

/**
 * The `ha` notice frame (#116) — parse, channel, and the one thing that actually
 * needed a review before it shipped: **what a button on a notification can
 * reach**.
 *
 * A notification lies on the lock screen, so a button on one is a way to act on
 * the household without unlocking unless every step of the path says otherwise.
 * The assertions below therefore run in both directions: what a notice *may* run
 * (a light, through the existing shortcut trampoline, behind the device lock),
 * and what it may *not* — a lock, a garage cover, an alarm panel or any string
 * that is not an allowlisted device gets no button at all, and a forged intent
 * carrying a lock still reaches nothing.
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
             "actions":[{"action":"light.keller","title":"Licht an"}]}
            """.trimIndent(),
        )!!
        assertEquals("Waschmaschine fertig", ev.title)
        assertEquals("Seit 5 Minuten", ev.body)
        assertEquals(NoticeCategory.HOUSE, ev.category)
        assertEquals("low", ev.urgency)
        assertEquals(listOf(NoticeAction("light.keller", "Licht an")), ev.actions)
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

    /** A bad button must not cost the notice its text. */
    @Test fun actionsAreCappedAndCleaned() {
        val ev = RealtimeProtocol.parseHa(
            """
            {"title":"T","actions":[
              {"action":"light.a","title":"A"},
              {"action":"","title":"leer"},
              {"action":"light.b"},
              "kaputt",
              {"action":"light.c","title":"C"},
              {"action":"light.d","title":"D"},
              {"action":"light.e","title":"E"}]}
            """.trimIndent(),
        )!!
        assertEquals(
            listOf("light.a", "light.c", "light.d"),
            ev.actions.map { it.action },
        )
    }

    // ---- what a notice may run ------------------------------------------

    @Test fun onlyAnAllowlistedDeviceGetsAButton() {
        assertTrue(NoticeActions.runnable(NoticeAction("light.keller", "An")))
        assertTrue(NoticeActions.runnable(NoticeAction("switch.pumpe", "An")))
        // The server's own examples: an entity id for a lock, and a service name.
        assertFalse(NoticeActions.runnable(NoticeAction("lock.front_door", "Schließen")))
        assertFalse(NoticeActions.runnable(NoticeAction("cover.close", "Schließen")))
        assertFalse(NoticeActions.runnable(NoticeAction("cover.garage", "Öffnen")))
        assertFalse(NoticeActions.runnable(NoticeAction("alarm_control_panel.haus", "Scharf")))
        assertFalse(NoticeActions.runnable(NoticeAction("lock.open", "Tür öffnen")))
        // Anything that is not an entity id at all.
        assertFalse(NoticeActions.runnable(NoticeAction("türöffnen", "Auf")))
        assertFalse(NoticeActions.runnable(NoticeAction("light.a.b", "An")))
        assertFalse(NoticeActions.runnable(NoticeAction("light.", "An")))
        assertFalse(NoticeActions.runnable(NoticeAction(".keller", "An")))
        assertFalse(NoticeActions.runnable(NoticeAction("light keller", "An")))
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
                    NoticeAction("lock.haustuer", "Abschließen"),
                    NoticeAction("cover.garage", "Schließen"),
                ),
            ),
        )
        assertEquals(0, n.actions?.size ?: 0)
        assertEquals("Haustür offen", n.extras.getString(Notification.EXTRA_TITLE))
    }

    /**
     * The one wired action: an **Activity** intent into the existing shortcut
     * trampoline — not a broadcast, which would fire straight off the lock screen
     * — and flagged as needing authentication on top of that.
     */
    @Test fun aNoticeButtonGoesThroughTheWidgetPath() {
        val n = post(
            RealtimeProtocol.NoticeEvent(
                "Waschmaschine fertig", "", NoticeCategory.HOUSE, "normal",
                listOf(NoticeAction("light.keller", "Licht an")),
            ),
        )
        assertEquals(1, n.actions.size)
        assertTrue(NotificationCompat.getAction(n, 0)!!.isAuthenticationRequired)
        val pi = shadowOf(n.actions[0].actionIntent)
        assertTrue("a notice button must launch an activity, never a broadcast", pi.isActivityIntent)
        assertFalse(pi.isBroadcastIntent)
        val intent = pi.savedIntent
        assertEquals(
            WidgetActionActivity::class.java.name,
            intent.component?.className,
        )
        assertTrue(intent.getBooleanExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, false))
        assertEquals("light.keller", intent.getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
        // …and that intent reaches exactly the light's own toggle.
        val taps = tapsFrom(intent)
        assertEquals(1, taps.size)
        assertEquals("light.keller", taps.single().getStringExtra(WidgetActionReceiver.EXTRA_ENTITY))
    }

    /**
     * The second half of the same guarantee: even a forged button aimed at a lock
     * — one this app would never build — runs nothing, because the trampoline
     * re-derives the domain from the entity id it was handed (#100).
     */
    @Test fun aForgedLockButtonReachesNothing() {
        val forged = Intent(ctx, WidgetActionActivity::class.java)
            .putExtra(WidgetActionReceiver.EXTRA_ENTITY, "lock.haustuer")
            .putExtra(WidgetActionActivity.EXTRA_SHORTCUT_TOGGLE, true)
        assertTrue(tapsFrom(forged).isEmpty())
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

    /** Post one notice and hand back the notification the system actually got. */
    private fun post(ev: RealtimeProtocol.NoticeEvent): Notification {
        val before = shadowOf(nm()).allNotifications.size
        NoticeNotifier.post(ctx, ev)
        val all = shadowOf(nm()).allNotifications
        assertEquals("expected exactly one new notification", before + 1, all.size)
        return all.last()
    }

    /** Everything the trampoline handed on for [intent]. */
    private fun tapsFrom(intent: Intent): List<Intent> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).clearBroadcastIntents()
        Robolectric.buildActivity(WidgetActionActivity::class.java, intent).create()
        return shadowOf(app).broadcastIntents.filter { it.action == WidgetActionReceiver.ACTION_TAP }
    }

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
