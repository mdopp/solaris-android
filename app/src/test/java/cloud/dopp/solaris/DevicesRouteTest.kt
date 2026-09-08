package cloud.dopp.solaris

import cloud.dopp.solaris.widget.PwaLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Where anything about devices lands (#167).
 *
 * The active-devices tile used to tap through to [PwaLauncher.Routes.ROOT] — the
 * chat. For a household that never types there, that is an empty screen and
 * answers nothing about the devices the tile is named after; the same empty
 * destination a notice tap had before #158.
 */
class DevicesRouteTest {

    @Test
    fun `the device route is the start page, not the chat`() {
        assertEquals("/#/p/start", PwaLauncher.Routes.START)
        assertNotEquals(PwaLauncher.Routes.ROOT, PwaLauncher.Routes.START)
    }

    /**
     * The tool-row fallback and the device route are the same page on purpose —
     * one household start page, not two ideas of where "everything" lives.
     */
    @Test
    fun `the tool fallback is that same page`() {
        assertEquals(PwaLauncher.Routes.START, PwaLauncher.Routes.TOOL_START)
    }

    /** Joined onto a paired base it stays a single well-formed URL. */
    @Test
    fun `it joins onto the paired base`() {
        assertEquals(
            "https://solaris.example/#/p/start",
            PwaLauncher.url("https://solaris.example", PwaLauncher.Routes.START),
        )
    }
}
