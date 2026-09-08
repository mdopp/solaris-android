package cloud.dopp.solaris

import cloud.dopp.solaris.realtime.NoticeBacklog
import cloud.dopp.solaris.realtime.RealtimeProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recognising a notice by its identity, not by its wording (#157).
 *
 * This is the end of #124. The content fingerprint could only ask "did something
 * with these words come by recently?" — and for a genuine repeat it answered yes,
 * so the second notice was swallowed. #155 merely shortened the window it was
 * wrong in. The server now mints one id per notice and puts it on **both** the
 * live frame and the catch-up entry (solarisbay#1346).
 *
 * The property that matters is not "an id is present" but **that it is used**:
 * two notices with identical wording must be two.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeIdentityTest {

    private fun notice(title: String, body: String, id: String? = null) =
        RealtimeProtocol.noticeOf(
            JSONObject().put("title", title).put("body", body).apply {
                if (id != null) put("id", id)
            },
        )!!

    /** The whole point: same words, different occurrences, never confused. */
    @Test
    fun `two identical notices are two`() {
        val first = NoticeBacklog.keysOf(notice("Tür", "offen", id = "aaa"))
        val second = NoticeBacklog.keysOf(notice("Tür", "offen", id = "bbb"))
        assertTrue("no key may be shared", first.none { it in second })
    }

    /** The same occurrence, live and again from the catch-up, IS one. */
    @Test
    fun `the same occurrence matches across both paths`() {
        val live = NoticeBacklog.keysOf(notice("Tür", "offen", id = "aaa"))
        val caughtUp = NoticeBacklog.keysOf(notice("Tür", "offen", id = "aaa"), "aaa")
        assertTrue("must recognise itself", live.any { it in caughtUp })
    }

    /** With an identity there is no fingerprint left to be wrong. */
    @Test
    fun `an identified notice carries no content key`() {
        val keys = NoticeBacklog.keysOf(notice("Tür", "offen", id = "aaa"))
        assertEquals(listOf("id:aaa"), keys)
    }

    /**
     * Against a server older than solarisbay#1346 the fingerprint still stands in
     * — a swallowed duplicate beats a notice that cannot be recognised at all.
     */
    @Test
    fun `without an id the fingerprint still stands in`() {
        val keys = NoticeBacklog.keysOf(notice("Tür", "offen"))
        assertEquals(1, keys.size)
        assertTrue("fingerprint expected, was $keys", keys.single().startsWith("fp:"))
    }

    /** An explicit backlog id wins over none on the event — the old call shape. */
    @Test
    fun `an explicit id is honoured when the event carries none`() {
        assertEquals(listOf("id:row-7"), NoticeBacklog.keysOf(notice("Tür", "offen"), "row-7"))
    }
}
