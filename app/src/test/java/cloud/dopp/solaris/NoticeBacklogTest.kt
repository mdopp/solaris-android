package cloud.dopp.solaris

import android.content.Context
import cloud.dopp.solaris.realtime.NoticeBacklog
import cloud.dopp.solaris.realtime.NoticeNotifier
import cloud.dopp.solaris.realtime.NoticeSeen
import cloud.dopp.solaris.realtime.RealtimeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The catch-up endpoint (#124): response in, notices-to-show out.
 *
 * Four rules carry the whole feature and each is asserted here rather than
 * observed on a phone: the next cursor comes from the response's `now` and never
 * from the device clock; `retention_hours` is read, never hard-coded; nothing is
 * shown twice; and a first run is bounded instead of replaying the whole window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeBacklogTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() = NoticeSeen.clear(ctx)

    // ---- the response ----------------------------------------------------

    /** Oldest first, the stream's own event verbatim, through the same renderer. */
    @Test fun aBacklogItemIsTheStreamsOwnEvent() {
        val out = NoticeBacklog.parse(
            response(
                notice(1, "2026-08-31T10:00:00.000Z", "Post da", category = "house"),
                notice(
                    2, "2026-08-31T10:01:00.000Z", "Garagentor offen", category = "house",
                    actions = """[{"entity_id":"cover.garagentor","service":"cover.close_cover",
                                   "title":"Schließen","confirm":true}]""",
                ),
            ),
            since = "2026-08-31T09:59:00.000Z",
            seen = emptySet(),
        )!!
        assertEquals(listOf("Post da", "Garagentor offen"), out.show.map { it.event.title })
        assertEquals(listOf("1", "2"), out.show.map { it.id })
        assertEquals("2026-08-31T10:01:00.000Z", out.show[1].ts)
        val action = out.show[1].event.actions.single()
        assertEquals("cover.garagentor", action.entityId)
        assertEquals("cover.close_cover", action.service)
        assertTrue(action.confirm)
    }

    /**
     * **The next `since` is the server's `now`, always.** Not the device clock,
     * and not only when the list comes back empty — a cursor advanced by our own
     * clock drifts against the server's and re-opens the very gap this closes.
     */
    @Test fun theNextCursorIsAlwaysTheServersNow() {
        val full = NoticeBacklog.parse(
            response(notice(7, "2026-08-31T10:00:00.000Z", "Post da")),
            since = "2026-08-31T09:00:00.000Z", seen = emptySet(),
        )!!
        assertEquals("2026-08-31T12:00:00.000Z", full.nextSince)
        val empty = NoticeBacklog.parse(
            response(), since = "2026-08-31T09:00:00.000Z", seen = emptySet(),
        )!!
        assertTrue(empty.show.isEmpty())
        assertEquals("2026-08-31T12:00:00.000Z", empty.nextSince)
        // A response that carries no usable `now` leaves the cursor where it was —
        // it is never invented from the device's own clock.
        val silent = NoticeBacklog.parse(
            """{"ok":true,"notifications":[],"retention_hours":6}""",
            since = "2026-08-31T09:00:00.000Z", seen = emptySet(),
        )!!
        assertEquals("2026-08-31T09:00:00.000Z", silent.nextSince)
    }

    /** The retention comes from the answer — the 6 hours live on the server. */
    @Test fun theRetentionIsReadAndNeverAssumed() {
        val out = NoticeBacklog.parse(
            response(now = "2026-08-31T12:00:00.000Z", retention = 6.0),
            since = "2026-08-31T11:00:00.000Z", seen = emptySet(),
        )!!
        assertEquals(6.0, out.retentionHours, 0.001)
        // A server with a different window is simply believed.
        val other = NoticeBacklog.parse(
            response(now = "2026-08-31T12:00:00.000Z", retention = 2.0),
            since = "2026-08-31T11:00:00.000Z", seen = emptySet(),
        )!!
        assertEquals(2.0, other.retentionHours, 0.001)
        // …and nothing in the source hard-codes it.
        val src = File(mainJava(), "cloud/dopp/solaris/realtime/NoticeBacklog.kt").readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
        assertTrue(
            "the retention window belongs to the server: $src",
            src.none { it.contains("RETENTION") || Regex("""\b6\s*\*\s*3600""").containsMatchIn(it) },
        )
    }

    /** Nothing to conclude → the cursor stays put, rather than skipping a window. */
    @Test fun anUnusableAnswerChangesNothing() {
        assertNull(NoticeBacklog.parse(null, null, emptySet()))
        assertNull(NoticeBacklog.parse("", null, emptySet()))
        assertNull(NoticeBacklog.parse("nicht json", null, emptySet()))
        assertNull(NoticeBacklog.parse("""{"ok":false,"reason":"invalid_since"}""", null, emptySet()))
    }

    // ---- nothing twice ---------------------------------------------------

    /** An id already delivered is not delivered again. */
    @Test fun anAlreadySeenIdIsDropped() {
        val body = response(
            notice(11, "2026-08-31T10:00:00.000Z", "Post da"),
            notice(12, "2026-08-31T10:05:00.000Z", "Waschmaschine fertig"),
        )
        val first = NoticeBacklog.parse(body, "2026-08-31T09:00:00.000Z", emptySet())!!
        assertEquals(2, first.show.size)
        val seen = first.show.flatMap { NoticeBacklog.keysOf(it.event, it.id) }.toSet()
        val again = NoticeBacklog.parse(body, "2026-08-31T09:00:00.000Z", seen)!!
        assertTrue("a notice must never arrive twice", again.show.isEmpty())
    }

    /**
     * The harder half: a notice the **live stream** already showed carries no id
     * at all, and its `ts` is newer than the stored cursor — so only the content
     * fingerprint keeps the backlog from showing it a second time minutes later.
     */
    @Test fun aNoticeTheStreamAlreadyShowedIsNotRepeated() {
        val live = RealtimeProtocol.parseHa(
            """{"title":"Post da","body":"im Kasten","category":"house","urgency":"normal"}""",
        )!!
        NoticeNotifier.post(ctx, live)
        val out = NoticeBacklog.parse(
            response(notice(21, "2026-08-31T10:00:00.000Z", "Post da", body = "im Kasten")),
            since = "2026-08-31T09:00:00.000Z",
            seen = NoticeSeen.keys(ctx),
        )!!
        assertTrue("the stream already showed it", out.show.isEmpty())
        // A different notice is of course still delivered.
        val other = NoticeBacklog.parse(
            response(notice(22, "2026-08-31T10:01:00.000Z", "Post da", body = "zwei Pakete")),
            since = "2026-08-31T09:00:00.000Z",
            seen = NoticeSeen.keys(ctx),
        )!!
        assertEquals(1, other.show.size)
        assertNotEquals(
            NoticeBacklog.keysOf(live).last(),
            NoticeBacklog.keysOf(other.show.single().event).last(),
        )
    }

    // ---- the first run ---------------------------------------------------

    /**
     * With no cursor the server hands back its whole window. Replaying six hours
     * of household chatter at once is its own kind of broken, so only the newest
     * few are shown — and the cursor is set from `now`, so the next pass is normal.
     */
    @Test fun aFirstRunIsBoundedRatherThanReplayed() {
        val many = (1..8).map { notice(it, "2026-08-31T0$it:00:00.000Z", "Meldung $it") }
        val out = NoticeBacklog.parse(response(*many.toTypedArray()), since = null, seen = emptySet())!!
        assertTrue(out.bounded)
        assertEquals(NoticeBacklog.FIRST_RUN_MAX, out.show.size)
        assertEquals(listOf("Meldung 6", "Meldung 7", "Meldung 8"), out.show.map { it.event.title })
        assertEquals("2026-08-31T12:00:00.000Z", out.nextSince)
    }

    /** A cursor older than the server's own window is the same flood by another route. */
    @Test fun aCursorOlderThanTheRetentionIsBoundedToo() {
        val many = (1..8).map { notice(it, "2026-08-31T0$it:00:00.000Z", "Meldung $it") }
        val stale = NoticeBacklog.parse(
            response(*many.toTypedArray(), now = "2026-08-31T12:00:00.000Z", retention = 6.0),
            since = "2026-08-30T20:00:00.000Z", seen = emptySet(),
        )!!
        assertTrue(stale.bounded)
        assertEquals(NoticeBacklog.FIRST_RUN_MAX, stale.show.size)
        // …while a cursor inside the window delivers everything it asked for.
        val fresh = NoticeBacklog.parse(
            response(*many.toTypedArray(), now = "2026-08-31T12:00:00.000Z", retention = 6.0),
            since = "2026-08-31T08:00:00.000Z", seen = emptySet(),
        )!!
        assertFalse(fresh.bounded)
        assertEquals(8, fresh.show.size)
    }

    /** The stamp parse the window check rests on, incl. the forms the server sends. */
    @Test fun theServerStampIsUnderstood() {
        val base = NoticeBacklog.epochMillis("2026-08-31T12:00:00.000Z")!!
        assertEquals(3_600_000L, NoticeBacklog.epochMillis("2026-08-31T13:00:00.000Z")!! - base)
        assertEquals(123L, NoticeBacklog.epochMillis("2026-08-31T12:00:00.123Z")!! - base)
        assertEquals(base, NoticeBacklog.epochMillis("2026-08-31 12:00:00.000"))
        assertEquals(base, NoticeBacklog.epochMillis("2026-08-31T12:00:00"))
        // A leap day and a year boundary, since the civil-days maths is hand-rolled.
        assertEquals(
            86_400_000L,
            NoticeBacklog.epochMillis("2024-03-01T00:00:00Z")!! -
                NoticeBacklog.epochMillis("2024-02-29T00:00:00Z")!!,
        )
        assertEquals(0L, NoticeBacklog.epochMillis("1970-01-01T00:00:00.000Z"))
        assertNull(NoticeBacklog.epochMillis(null))
        assertNull(NoticeBacklog.epochMillis("gestern"))
    }

    // ---- what is remembered ----------------------------------------------

    /** The cursor and the shown-list survive a wake, and stay bounded. */
    @Test fun theCursorAndTheHistoryPersist() {
        assertNull(NoticeSeen.since(ctx))
        NoticeSeen.setSince(ctx, "2026-08-31T12:00:00.000Z")
        assertEquals("2026-08-31T12:00:00.000Z", NoticeSeen.since(ctx))
        // A blank `now` never overwrites a good cursor.
        NoticeSeen.setSince(ctx, "")
        assertEquals("2026-08-31T12:00:00.000Z", NoticeSeen.since(ctx))
        NoticeSeen.mark(ctx, (1..NoticeSeen.MAX_KEYS + 20).map { "id:$it" })
        val keys = NoticeSeen.keys(ctx)
        assertEquals(NoticeSeen.MAX_KEYS, keys.size)
        assertTrue("the newest are kept", "id:${NoticeSeen.MAX_KEYS + 20}" in keys)
        assertFalse("the oldest fall off", "id:1" in keys)
    }

    // ---- helpers ---------------------------------------------------------

    private fun notice(
        id: Int,
        ts: String,
        title: String,
        body: String = "",
        category: String = "house",
        actions: String = "[]",
    ): String =
        """{"kind":"ha","target":"michael","title":"$title","body":"$body",
            "urgency":"normal","category":"$category","actions":$actions,
            "id":$id,"ts":"$ts"}"""

    private fun response(
        vararg items: String,
        now: String = "2026-08-31T12:00:00.000Z",
        retention: Double = 6.0,
    ): String =
        """{"ok":true,"notifications":[${items.joinToString(",")}],
            "now":"$now","retention_hours":$retention,"delivery":"best_effort"}"""

    private fun mainJava(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src/main"), File(dir, "app/src/main"))) {
                if (candidate.isDirectory) return File(candidate, "java")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate src/main from ${File("").absolutePath}")
    }
}
