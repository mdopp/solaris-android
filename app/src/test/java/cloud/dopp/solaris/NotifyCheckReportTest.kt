package cloud.dopp.solaris

import cloud.dopp.solaris.ui.NotifyCheckReport
import cloud.dopp.solaris.widget.PwaLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure coverage for the two halves of `sb-notify-tap`:
 *
 * - the ServiceBay tap target (#109) — the widgets must resolve to the ServiceBay
 *   **admin host**, the target settled in #45, not to a path under the Solaris base
 *   (which is how a tap on a ServiceBay tile ended up in the chat), with the
 *   unpaired case still falling back to the old route;
 * - the diagnostics self-test report (#110) — the text that makes the alert path
 *   readable without a pending update on the box.
 */
class NotifyCheckReportTest {

    // --- ServiceBay tap target (#109) ---

    @Test
    fun serviceBayUrlIsTheAdminHostNotTheSolarisBase() {
        assertEquals("https://admin.dopp.cloud", PwaLauncher.serviceBayUrl("https://chat.dopp.cloud"))
        // The bug: joining the route onto the base kept the tap on the chat host.
        assertEquals(
            "https://chat.dopp.cloud/",
            PwaLauncher.url("https://chat.dopp.cloud", PwaLauncher.Routes.SERVICEBAY),
        )
    }

    @Test
    fun serviceBayUrlToleratesTrailingSlashAndMissingScheme() {
        assertEquals("https://admin.dopp.cloud", PwaLauncher.serviceBayUrl("https://chat.dopp.cloud/"))
        assertEquals("https://admin.dopp.cloud", PwaLauncher.serviceBayUrl("chat.dopp.cloud"))
    }

    @Test
    fun serviceBayUrlIsNullWithoutAPairedServer() {
        // Null → the caller keeps the base-relative fallback (onboarding bounce).
        assertEquals(null, PwaLauncher.serviceBayUrl(null))
        assertEquals(null, PwaLauncher.serviceBayUrl(""))
        assertEquals(null, PwaLauncher.serviceBayUrl("   "))
    }

    @Test
    fun serviceBayUrlMatchesTheUpdatesNotificationTarget() {
        // #109 exists because the tiles did not use what #45/PR #83 already decided.
        assertEquals(
            SolarisConfig.adminUrl("https://chat.dopp.cloud"),
            PwaLauncher.serviceBayUrl("https://chat.dopp.cloud"),
        )
    }

    /**
     * The regression itself was not in the derivation but in **who calls it** — the
     * helper existed since PR #83 and the tiles simply did not use it. Asserted over
     * the sources, the way the notification-icon rule is (#88).
     */
    @Test
    fun bothServiceBayWidgetsTapThroughTheSharedHelper() {
        for (name in listOf("SbOverviewWidgetProvider.kt", "SbServicesWidgetProvider.kt")) {
            val src = File(mainDir, "java/cloud/dopp/solaris/widget/$name")
            assertTrue("missing $name", src.isFile)
            val text = src.readText()
            assertTrue("$name must tap via PwaLauncher.serviceBayTap (#109)", text.contains("PwaLauncher.serviceBayTap("))
            assertTrue(
                "$name must not join Routes.SERVICEBAY onto the Solaris base again (#109)",
                !text.contains("Routes.SERVICEBAY"),
            )
        }
    }

    private val mainDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src/main"), File(dir, "app/src/main"))) {
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate src/main from ${File("").absolutePath}")
    }

    // --- Diagnostics self-test report (#110) ---

    @Test
    fun reportShowsCountsAndTapTarget() {
        val text = NotifyCheckReport.format("https://chat.dopp.cloud", 2, 5, null)
        assertTrue(text, text.contains("Freigaben offen: 2"))
        assertTrue(text, text.contains("Updates ausstehend: 5"))
        assertTrue(text, text.contains("Tippziel: https://admin.dopp.cloud"))
        assertTrue(text, text.contains("Grundlinie zurückgesetzt"))
    }

    @Test
    fun reportShowsZeroCountsRatherThanHidingThem() {
        // Nothing pending is the normal case — and exactly the one that used to make
        // the path unverifiable, so the numbers must still be spelled out.
        val text = NotifyCheckReport.format("https://chat.dopp.cloud", 0, 0, null)
        assertTrue(text, text.contains("Freigaben offen: 0"))
        assertTrue(text, text.contains("Updates ausstehend: 0"))
    }

    @Test
    fun reportCarriesTheFetchErrorInsteadOfCounts() {
        val text = NotifyCheckReport.format("https://chat.dopp.cloud", null, null, "timeout")
        assertTrue(text, text.contains("Abruf fehlgeschlagen: timeout"))
        assertTrue(text, !text.contains("Freigaben offen:"))
        // The tap target is derivable without the server, so it stays visible.
        assertTrue(text, text.contains("Tippziel: https://admin.dopp.cloud"))
    }

    @Test
    fun reportNamesAFallbackReasonWhenTheErrorIsBlank() {
        val text = NotifyCheckReport.format("https://chat.dopp.cloud", null, null, "")
        assertTrue(text, text.contains("Abruf fehlgeschlagen: unbekannter Fehler"))
    }

    @Test
    fun reportSaysSoWhenNoServerIsPaired() {
        val text = NotifyCheckReport.format(null, null, null, "kein Server")
        assertEquals(NotifyCheckReport.NO_SERVER, NotifyCheckReport.target(null))
        assertTrue(text, text.contains("Tippziel: " + NotifyCheckReport.NO_SERVER))
    }

    @Test
    fun reportWarnsWhenNotificationsAreOff() {
        // Otherwise "test ran, nothing appeared" is indistinguishable from a bug.
        val off = NotifyCheckReport.format("https://chat.dopp.cloud", 1, 1, null, notificationsEnabled = false)
        assertTrue(off, off.contains("Benachrichtigungen sind für Solaris aus"))
        val on = NotifyCheckReport.format("https://chat.dopp.cloud", 1, 1, null, notificationsEnabled = true)
        assertTrue(on, !on.contains("Benachrichtigungen sind für Solaris aus"))
    }
}
