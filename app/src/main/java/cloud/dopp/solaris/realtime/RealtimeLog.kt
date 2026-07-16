package cloud.dopp.solaris.realtime

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny persisted ring buffer of realtime connection events (#48) — connected /
 * disconnected (+reason) / sleep / wake — so the user (and we) can judge whether
 * reconnects are occasional-and-normal or a real churn worth a server ticket. Kept
 * to the last [MAX] lines in plain prefs; viewable in DiagnosticsActivity.
 */
object RealtimeLog {
    private const val PREFS = "realtime_log"
    private const val KEY = "lines"
    private const val MAX = 50
    private const val SEP = "\n"

    fun add(context: Context, event: String) {
        runCatching {
            val ctx = context.applicationContext
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.GERMANY).format(Date())
            val existing = prefs.getString(KEY, "").orEmpty()
            val lines = (existing.split(SEP).filter { it.isNotBlank() } + "$stamp  $event")
                .takeLast(MAX)
            prefs.edit().putString(KEY, lines.joinToString(SEP)).apply()
        }
    }

    /** The log, newest first, as one string (empty if nothing yet). */
    fun formatted(context: Context): String {
        val ctx = context.applicationContext
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return raw.split(SEP).filter { it.isNotBlank() }.asReversed().joinToString("\n")
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
