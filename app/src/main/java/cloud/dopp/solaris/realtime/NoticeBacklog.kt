package cloud.dopp.solaris.realtime

import org.json.JSONObject

/**
 * The catch-up half of the notice path (#124, server side solarisbay#1286,
 * shipped v0.48.0): `GET /napi/notifications?since=<ts>` answers what the phone
 * missed while nothing was listening.
 *
 * Pure (no Android) — response in, notices-to-show out — so every rule below is a
 * JVM test rather than a device observation.
 *
 * ## The three rules that are easy to lose
 *
 * 1. **`now` from the response is the next `since` — never the device clock.**
 *    Always, not only when the list comes back empty. A cursor advanced by our own
 *    clock drifts against the server's and re-opens exactly the gap this endpoint
 *    exists to close. When the response carries no usable `now`, the old cursor
 *    stays; it is never invented here.
 * 2. **`retention_hours` is read from the response, never hard-coded.** A limit
 *    kept in two places drifts apart. The short retention is a **privacy**
 *    decision and not a storage compromise — these payloads name residents and
 *    describe what happens in their homes — so the number belongs to the side that
 *    prunes, and we only obey it.
 * 3. **Nothing is shown twice.** `id` exists for that, and [keysOf] adds a
 *    content fingerprint so a notice already shown *from the live stream* — which
 *    carries no id at all — is recognised in the backlog too.
 *
 * ## The first run is bounded, not replayed
 *
 * With no stored cursor (a fresh install, cleared data) the server returns its
 * whole window, and replaying six hours of household chatter at once is its own
 * kind of broken. So a run without a usable cursor shows only the newest
 * [FIRST_RUN_MAX] — a bound taken from the response itself, with no device clock
 * anywhere in it. The same applies when the stored cursor is older than the
 * server's own retention: everything inside the window is then "new" to us, which
 * is the same flood by another route.
 *
 * This does **not** make the channel an alarm channel — see
 * [RealtimeProtocol.EVENT_HA], which is unchanged. What changed is only that a
 * screen-off gap shorter than the server's retention is now survivable.
 */
object NoticeBacklog {

    /** `/napi` path of the catch-up endpoint. */
    const val PATH = "/notifications"

    /** How many notices a cursor-less pass may show at once. */
    const val FIRST_RUN_MAX = 3

    /** One backlogged notice: the stream's event verbatim, plus its id and stamp. */
    data class Item(
        val id: String,
        val ts: String,
        val event: RealtimeProtocol.NoticeEvent,
    )

    /**
     * What one catch-up pass concluded: which notices to post ([show], oldest
     * first), which cursor to store next ([nextSince]) and what the server said
     * its retention is ([retentionHours], 0 when it did not say).
     *
     * [bounded] records that [show] was capped — the burst guard fired.
     */
    data class CatchUp(
        val show: List<Item>,
        val nextSince: String?,
        /** How many the server DID return but [parse] dropped as already shown (#155). */
        val alreadySeen: Int = 0,
        val retentionHours: Double,
        val bounded: Boolean,
    )

    /**
     * The keys under which a notice counts as "already shown". Two of them,
     * because the two delivery paths know different things about the same event:
     *
     * - `id:<id>` — the backlog's own opaque, monotonic id. Exact, but a live
     *   stream frame does not carry one.
     * - a **content fingerprint** — what both paths do have. Without it a notice
     *   shown live at 10:00 would be shown again by the 10:04 catch-up, because
     *   its `ts` is newer than the cursor we last stored.
     *
     * The fingerprint's cost is that a genuinely repeated, byte-identical notice
     * inside the retention window is swallowed once. That is the better failure:
     * a doubled notice is a bug the resident sees every single time.
     */
    fun keysOf(ev: RealtimeProtocol.NoticeEvent, id: String? = null): List<String> {
        val print = buildString {
            append(ev.category.wire).append('|')
            append(ev.urgency).append('|')
            append(ev.title).append('|')
            append(ev.body).append('|')
            ev.actions.forEach { append(it.entityId).append('>').append(it.service).append(',') }
        }
        val keys = ArrayList<String>(2)
        if (!id.isNullOrBlank()) keys.add("id:$id")
        keys.add("fp:${print.hashCode()}")
        return keys
    }

    /**
     * Read one `GET /napi/notifications` response. Returns null when there is
     * nothing to conclude — no body, malformed JSON, or `ok:false` — so the caller
     * leaves its cursor exactly where it was rather than skipping a window it
     * never saw.
     *
     * [since] is the stored cursor (null on a first run) and [seen] the keys of
     * notices already shown; both only ever *remove* work.
     */
    fun parse(body: String?, since: String?, seen: Set<String>): CatchUp? {
        if (body.isNullOrBlank()) return null
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        if (root.has("ok") && !root.optBoolean("ok", false)) return null
        val now = root.optString("now").trim()
        val retention = root.optDouble("retention_hours", 0.0).let { if (it.isNaN()) 0.0 else it }
        val arr = root.optJSONArray("notifications")
        val fresh = ArrayList<Item>()
        var dropped = 0
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr?.optJSONObject(i) ?: continue
            // The same renderer the stream uses — there is no second one to drift.
            val ev = RealtimeProtocol.noticeOf(o) ?: continue
            val id = o.optString("id").trim()
            val item = Item(id, o.optString("ts").trim(), ev)
            if (keysOf(ev, id).any { it in seen }) { dropped++; continue }
            fresh.add(item)
        }
        val bounded = !hasUsableCursor(since, now, retention)
        return CatchUp(
            show = if (bounded) fresh.takeLast(FIRST_RUN_MAX) else fresh,
            // Rule 1: the cursor comes from the server's clock or not at all.
            nextSince = now.ifBlank { since },
            retentionHours = retention,
            bounded = bounded,
            alreadySeen = dropped,
        )
    }

    /**
     * Is [since] a cursor worth trusting to bound this pass — parseable, and (when
     * both the server's [now] and its [retentionHours] are known) still inside the
     * window it prunes to? A cursor older than the retention would let the whole
     * window through as "new".
     */
    fun hasUsableCursor(since: String?, now: String, retentionHours: Double): Boolean {
        val from = epochMillis(since) ?: return false
        val to = epochMillis(now)
        if (to == null || retentionHours <= 0.0) return true // nothing to compare against
        return to - from <= (retentionHours * 3_600_000L).toLong()
    }

    /** `2026-08-30T01:02:03.123Z` (or the plain SQLite form) as epoch millis. */
    fun epochMillis(raw: String?): Long? {
        val g = STAMP.matchEntire(raw?.trim().orEmpty())?.groupValues ?: return null
        val days = daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
        val frac = g[7]
        val millis = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').substring(0, 3).toInt()
        val seconds = days * 86_400L + g[4].toInt() * 3600L + g[5].toInt() * 60L + g[6].toInt()
        return seconds * 1000L + millis
    }

    private val STAMP =
        Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?Z?$""")

    /**
     * Days since 1970-01-01 for a proleptic-Gregorian date (Howard Hinnant's
     * `days_from_civil`). Hand-rolled because `java.time` needs API 26 and this
     * app still runs on 23 — and because a pure function is testable.
     */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146_097L + doe.toLong() - 719_468L
    }
}
