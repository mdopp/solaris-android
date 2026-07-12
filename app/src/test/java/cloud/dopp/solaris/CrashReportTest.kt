package cloud.dopp.solaris

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the crash-report URL/body builder — no Android, so no
 * Robolectric needed. Covers title extraction, trace truncation, and that the
 * new-issue URL carries the URL-encoded trace + app/device metadata + label.
 */
class CrashReportTest {

    private val trace = """
        Solaris crash

        java.lang.IllegalStateException: boom
            at cloud.dopp.solaris.ui.OnboardingHomeActivity.onCreate(OnboardingHomeActivity.kt:57)
    """.trimIndent()

    @Test
    fun title_usesFirstMeaningfulLine_skippingBanner() {
        assertEquals(
            "Crash: java.lang.IllegalStateException: boom",
            CrashReport.titleFor(trace),
        )
    }

    @Test
    fun truncate_appendsMarkerWhenOverLimit() {
        val long = "x".repeat(CrashReport.MAX_TRACE_CHARS + 500)
        val out = CrashReport.truncateTrace(long)
        assertTrue(out.length < long.length)
        assertTrue(out.endsWith("(gekürzt)"))
    }

    @Test
    fun truncate_leavesShortTraceUntouched() {
        assertEquals("short", CrashReport.truncateTrace("short"))
    }

    @Test
    fun issueUrl_carriesEncodedTitleBodyAndLabel() {
        val url = CrashReport.issueUrl(
            trace = trace,
            appVersion = "2.7.0",
            device = "Pixel 7",
            androidVersion = "Android 14",
        )
        assertTrue(url.startsWith(CrashReport.NEW_ISSUE_URL + "?"))
        assertTrue(url.contains("labels=" + CrashReport.LABEL))

        val query = url.substringAfter("?")
        val params = query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        assertTrue(params.getValue("title").startsWith("Crash:"))
        val body = params.getValue("body")
        assertTrue(body.contains("2.7.0"))
        assertTrue(body.contains("Pixel 7"))
        assertTrue(body.contains("Android 14"))
        assertTrue(body.contains("IllegalStateException"))
    }
}
