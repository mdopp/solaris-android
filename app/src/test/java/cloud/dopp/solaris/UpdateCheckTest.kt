package cloud.dopp.solaris

import cloud.dopp.solaris.data.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update hint's two pure decisions (#141): **is it actually newer**, and
 * **where may the tap go**.
 *
 * Both fail quietly rather than wrongly. An update line that offers a downgrade,
 * or that leads somewhere the server didn't earn, is worse than no line — the
 * whole reason this waited for solarisbay#1326, where `/download` promised a
 * "stable install link" and answered 404.
 */
class UpdateCheckTest {

    // --- newer or not ---------------------------------------------------------

    /**
     * The trap this exists for: compared as strings, "2.4.0" sorts AFTER "2.38.1",
     * so a string compare would offer 2.4.0 as an update to someone on 2.38.1.
     */
    @Test
    fun `compares segments numerically, not alphabetically`() {
        assertFalse("2.4.0 is older than 2.38.1", UpdateCheck.isNewer("2.4.0", "2.38.1"))
        assertTrue("2.38.1 is newer than 2.4.0", UpdateCheck.isNewer("2.38.1", "2.4.0"))
    }

    @Test
    fun `only a strictly newer version counts`() {
        assertTrue(UpdateCheck.isNewer("2.38.2", "2.38.1"))
        assertFalse("same version is not an update", UpdateCheck.isNewer("2.38.1", "2.38.1"))
        assertFalse("older is never an update", UpdateCheck.isNewer("2.38.0", "2.38.1"))
    }

    /** A missing segment is 0, so a shorter version can still win — or lose. */
    @Test
    fun `handles differing segment counts`() {
        assertTrue(UpdateCheck.isNewer("2.39", "2.38.1"))
        assertFalse(UpdateCheck.isNewer("2.38", "2.38.1"))
        assertTrue(UpdateCheck.isNewer("2.38.1", "2.38"))
    }

    /** A `v` prefix or a suffix on a segment must not derail the comparison. */
    @Test
    fun `tolerates a tag prefix and a segment suffix`() {
        assertTrue(UpdateCheck.isNewer("v2.38.2", "2.38.1"))
        assertFalse(UpdateCheck.isNewer("2.38.1-rc2", "2.38.1"))
        assertTrue(UpdateCheck.isNewer("2.39.0-rc1", "2.38.1"))
    }

    /** Unparseable input is silence, never a guess in either direction. */
    @Test
    fun `stays silent on nonsense`() {
        assertFalse(UpdateCheck.isNewer("", "2.38.1"))
        assertFalse(UpdateCheck.isNewer("latest", "2.38.1"))
        assertFalse(UpdateCheck.isNewer("2.38.1", ""))
        assertFalse(UpdateCheck.isNewer("2.38.1", "?"))
    }

    // --- where the tap goes ---------------------------------------------------

    private val base = "https://solaris.example"

    /** The file and the API need not share a host, so the server's answer wins. */
    @Test
    fun `honours an https url from the server`() {
        assertEquals(
            "https://files.example/solaris.apk",
            UpdateCheck.downloadUrl("https://files.example/solaris.apk", base),
        )
    }

    /**
     * But only over https. A plaintext or exotic scheme in a response must not
     * become something the app opens; it falls back to the paired server.
     */
    @Test
    fun `refuses anything that is not https`() {
        for (bad in listOf("http://files.example/a.apk", "market://details?id=x", "javascript:alert(1)", "", "   ")) {
            assertEquals("rejected: $bad", "$base/download", UpdateCheck.downloadUrl(bad, base))
        }
        assertEquals("$base/download", UpdateCheck.downloadUrl(null, base))
    }
}
