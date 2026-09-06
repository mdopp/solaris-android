package cloud.dopp.solaris

import cloud.dopp.solaris.realtime.NoticeHistory
import cloud.dopp.solaris.realtime.RealtimeProtocol
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What Solaris said, kept on the phone (#158).
 *
 * A notification is gone the moment it is swiped away, and the text goes with it —
 * which is also why a tap had no destination and landed on the empty chat. This
 * pins the two properties that make the list trustworthy: it holds what arrived,
 * and it does **not** quietly become a permanent record of the household's day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeHistoryTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val t0 = 1_800_000_000_000L

    @After fun tearDown() = NoticeHistory.clear(ctx)

    private fun notice(title: String, body: String = "…") =
        RealtimeProtocol.noticeOf(JSONObject().put("title", title).put("body", body))!!

    @Test
    fun `keeps what arrived, newest first`() {
        NoticeHistory.record(ctx, notice("Erste"), t0)
        NoticeHistory.record(ctx, notice("Zweite"), t0 + 1000)
        val all = NoticeHistory.all(ctx, t0 + 2000)
        assertEquals(listOf("Zweite", "Erste"), all.map { it.title })
    }

    @Test
    fun `keeps the body, which is the whole point`() {
        NoticeHistory.record(ctx, notice("Titel", "Der Text, den die Benachrichtigung mitnahm"), t0)
        assertEquals(
            "Der Text, den die Benachrichtigung mitnahm",
            NoticeHistory.all(ctx, t0 + 1).single().body,
        )
    }

    /** Bounded by count — never an unbounded log of the household. */
    @Test
    fun `holds at most MAX_ENTRIES`() {
        repeat(NoticeHistory.MAX_ENTRIES + 20) { i ->
            NoticeHistory.record(ctx, notice("N$i"), t0 + i)
        }
        val all = NoticeHistory.all(ctx, t0 + 10_000)
        assertEquals(NoticeHistory.MAX_ENTRIES, all.size)
        assertTrue("the newest survive", all.first().title.endsWith("119"))
    }

    /** Bounded by age too — a week, then it is gone without anyone asking. */
    @Test
    fun `forgets anything older than the window`() {
        NoticeHistory.record(ctx, notice("Alt"), t0)
        NoticeHistory.record(ctx, notice("Neu"), t0 + NoticeHistory.MAX_AGE_MS)
        val all = NoticeHistory.all(ctx, t0 + NoticeHistory.MAX_AGE_MS + 1)
        assertEquals(listOf("Neu"), all.map { it.title })
    }

    @Test
    fun `clearing really clears`() {
        NoticeHistory.record(ctx, notice("Weg"), t0)
        NoticeHistory.clear(ctx)
        assertTrue(NoticeHistory.all(ctx, t0 + 1).isEmpty())
    }
}
