package cloud.dopp.solaris

import cloud.dopp.solaris.realtime.NoticeSeen
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How long "already shown" lasts (#155).
 *
 * A notice off the live stream carries **no id**, so the only thing identifying
 * it is a fingerprint over its content. Kept forever, that made any notice with
 * the same wording as an earlier one invisible for good — "Waschmaschine fertig"
 * twice, the same test sent twice, gone the second time. The fingerprint is only
 * meant to stop ONE occurrence being shown twice (live and again from the
 * catch-up), which is a window of minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeSeenTtlTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val t0 = 1_000_000_000_000L
    private val ttl = NoticeSeen.FINGERPRINT_TTL_MS

    @After fun tearDown() = NoticeSeen.clear(ctx)

    @Test
    fun `a fingerprint suppresses a duplicate within the window`() {
        NoticeSeen.mark(ctx, listOf("fp:123"), t0)
        assertTrue(NoticeSeen.keys(ctx, t0 + ttl / 2).contains("fp:123"))
    }

    /** The regression: the same wording later is a NEW event, not a duplicate. */
    @Test
    fun `a fingerprint stops suppressing once the window passes`() {
        NoticeSeen.mark(ctx, listOf("fp:123"), t0)
        assertFalse(NoticeSeen.keys(ctx, t0 + ttl + 1).contains("fp:123"))
    }

    /** An id names one notice and can never hide another, so it does not expire. */
    @Test
    fun `an id keeps counting as seen`() {
        NoticeSeen.mark(ctx, listOf("id:abc"), t0)
        assertTrue(NoticeSeen.keys(ctx, t0 + ttl * 100).contains("id:abc"))
    }

    /** Seeing it again restarts the window — "shown recently", not "ever seen". */
    @Test
    fun `a repeat re-stamps the fingerprint`() {
        NoticeSeen.mark(ctx, listOf("fp:123"), t0)
        NoticeSeen.mark(ctx, listOf("fp:123"), t0 + ttl - 1)
        assertTrue(NoticeSeen.keys(ctx, t0 + ttl + 1).contains("fp:123"))
    }

    /**
     * Entries written before #155 carry no stamp. A fingerprint without one
     * expires immediately — the safe direction, because a stale fingerprint hides
     * a real notice, while a lost one costs at most one duplicate.
     */
    @Test
    fun `unstamped entries fail safe`() {
        ctx.getSharedPreferences("notice_catchup", 0).edit()
            // The store separates keys with NUL, not a space (`NoticeSeen.SEP`) —
            // a space-separated fixture is read back as ONE key and proves nothing.
            .putString("seen", "fp:999\u0000id:old").commit()
        val keys = NoticeSeen.keys(ctx, t0)
        assertFalse("stale fingerprint must not hide a notice", keys.contains("fp:999"))
        assertTrue("an id stays valid", keys.contains("id:old"))
    }
}
