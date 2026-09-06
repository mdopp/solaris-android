package cloud.dopp.solaris.realtime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Solaris told this household, kept on the phone (#158).
 *
 * A notification is gone the moment it is swiped away — and with it the only copy
 * of what it said. There was nowhere in the app to look it up, which is also why a
 * tap on a notice had no sensible destination and landed on the empty chat.
 *
 * Written from [NoticeNotifier.post], the single funnel both the live stream and
 * the catch-up pass through, so there is no second recorder that can drift.
 *
 * ## Deliberately bounded
 *
 * [NoticeSeen] keeps its keys short on purpose — "rather than accumulating a
 * record of the household's day". Keeping the notices themselves is a step in the
 * other direction and is taken knowingly, so it is bounded twice: at most
 * [MAX_ENTRIES], and nothing older than [MAX_AGE_MS]. It lives in the app's
 * private storage behind the device lock, and never leaves the phone.
 */
object NoticeHistory {

    private const val PREFS = "notice_history"
    private const val KEY = "entries"

    /** Never more than this many, newest kept. */
    const val MAX_ENTRIES = 100

    /** Never older than this — a week is plenty to answer "what was that?". */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** One notice as it was shown. [atMs] is the device clock at delivery. */
    data class Entry(
        val title: String,
        val body: String,
        val category: String,
        val urgency: String,
        val atMs: Long,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Append one notice. Never throws into the notification path. */
    fun record(ctx: Context, ev: RealtimeProtocol.NoticeEvent, atMs: Long = System.currentTimeMillis()) {
        runCatching {
            val entry = JSONObject()
                .put("title", ev.title)
                .put("body", ev.body)
                .put("category", ev.category.wire)
                .put("urgency", ev.urgency)
                .put("at", atMs)
            val kept = readArray(ctx, atMs)
            kept.put(entry)
            val trimmed = JSONArray()
            val from = maxOf(0, kept.length() - MAX_ENTRIES)
            for (i in from until kept.length()) trimmed.put(kept.optJSONObject(i) ?: continue)
            prefs(ctx.applicationContext).edit().putString(KEY, trimmed.toString()).apply()
        }
    }

    /** Newest first — the order a reader wants. Empty when nothing is kept. */
    fun all(ctx: Context, nowMs: Long = System.currentTimeMillis()): List<Entry> {
        val arr = readArray(ctx, nowMs)
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    title = o.optString("title"),
                    body = o.optString("body"),
                    category = o.optString("category"),
                    urgency = o.optString("urgency"),
                    atMs = o.optLong("at", 0L),
                ),
            )
        }
        return out.asReversed()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    /** The stored array with anything past [MAX_AGE_MS] already dropped. */
    private fun readArray(ctx: Context, nowMs: Long): JSONArray {
        val raw = prefs(ctx).getString(KEY, null) ?: return JSONArray()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val at = o.optLong("at", 0L)
            // A missing/zero stamp cannot be aged, so it is dropped rather than
            // kept forever — the same fail-safe direction as NoticeSeen (#155).
            if (at <= 0L || nowMs - at > MAX_AGE_MS) continue
            out.put(o)
        }
        return out
    }
}
