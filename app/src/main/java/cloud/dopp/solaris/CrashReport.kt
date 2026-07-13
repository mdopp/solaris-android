package cloud.dopp.solaris

import java.net.URLEncoder

/**
 * Pure, JVM-testable helpers for the crash-report flow. Kept free of Android
 * types so [cloud.dopp.solaris.CrashReportTest] can exercise the URL-building
 * and truncation without an emulator.
 *
 * The report is delivered by pointing a Custom Tab at GitHub's *new issue* form
 * with a prefilled title + body (no login/token needed — GitHub reads the query
 * params). GitHub URLs have a practical length cap, so the trace is truncated.
 */
object CrashReport {

    /** github.com/mdopp/solaris-android — the repo issues are filed against. */
    const val NEW_ISSUE_URL = "https://github.com/mdopp/solaris-android/issues/new"

    /** Label applied to the prefilled issue so crash reports are triageable. */
    const val LABEL = "crash"

    /** Keep the trace well under GitHub's ~8 KB URL limit once URL-encoded. */
    const val MAX_TRACE_CHARS = 1500

    /**
     * First line of the crash text, minus the "Solaris crash" banner
     * [SolarisApp] prepends — used as the issue title so it reads like a summary.
     */
    fun titleFor(trace: String): String {
        val line = trace.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("Solaris crash", ignoreCase = true) }
            ?: "Unbekannter Fehler"
        return "Crash: " + line.take(120)
    }

    /** Truncate to [MAX_TRACE_CHARS], appending a marker when it was cut. */
    fun truncateTrace(trace: String, max: Int = MAX_TRACE_CHARS): String {
        if (trace.length <= max) return trace
        return trace.take(max).trimEnd() + "\n… (gekürzt)"
    }

    /**
     * Build the report body: a short human note, the app/device metadata block,
     * and the (truncated) trace in a fenced code block.
     */
    fun bodyFor(trace: String, appVersion: String, device: String, androidVersion: String): String =
        buildString {
            append("Automatisch aus der Solaris-App gemeldet.\n\n")
            append("- **App-Version:** ").append(appVersion).append('\n')
            append("- **Gerät:** ").append(device).append('\n')
            append("- **Android:** ").append(androidVersion).append("\n\n")
            append("**Stacktrace:**\n\n")
            append("```\n")
            append(truncateTrace(trace))
            append("\n```\n")
        }

    /**
     * Full new-issue URL with URL-encoded `title`, `body` and `labels` params.
     * Opened via the existing Custom Tab / VIEW-intent helper.
     */
    fun issueUrl(trace: String, appVersion: String, device: String, androidVersion: String): String {
        val title = enc(titleFor(trace))
        val body = enc(bodyFor(trace, appVersion, device, androidVersion))
        val labels = enc(LABEL)
        return "$NEW_ISSUE_URL?title=$title&body=$body&labels=$labels"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
