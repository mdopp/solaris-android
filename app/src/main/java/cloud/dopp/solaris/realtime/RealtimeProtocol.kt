package cloud.dopp.solaris.realtime

import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.Card
import org.json.JSONObject

/**
 * Pure (Android-free) helpers for the realtime SSE client (#48): the reconnect
 * backoff schedule and the `card_state` frame parse. Kept off any Android type so
 * they're covered by plain JVM unit tests.
 */
object RealtimeProtocol {

    /** The SSE event kind we act on; every other kind (chat/done/…) is ignored. */
    /** The one [NoticeEvent.kind] the app treats specially (#143). */
    const val KIND_APP_UPDATE = "app-update"

    const val EVENT_CARD_STATE = "card_state"

    /** ServiceBay approval event (companion-api.md): `data:{id,kind,summary}`. */
    const val EVENT_SERVICEBAY = "servicebay"

    /**
     * A household notice for this resident (#116; server side solarisbay#1276 +
     * #1280, shipped in v0.46.0/v0.47.0):
     * `data:{kind:"ha", target, title, body, urgency, actions, category}`.
     *
     * One kind carries three things — a notice an HA automation posted, a fired
     * timer/Wecker, and a fired reminder — told apart only by [NoticeCategory].
     * That is deliberate on both sides: mechanically it is the same event, and two
     * kinds meant two places for the same class of bug.
     *
     * ## NOT AN ALARM CHANNEL — for every category, timers and reminders included
     *
     * Delivery is **best effort and always will be**. A notice arrives at once
     * while [RealtimeService] holds the stream; with the screen off the stream is
     * dropped for battery, and the poll pass ([NoticeCatchUp]) both listens
     * briefly and asks the server's short backlog what it missed (#124). That
     * moved the promise from "can be lost while the screen is off" to **"catches
     * up if the gap is under the server's retention"** — and no further: past the
     * window, past the per-stream row cap, or with nothing ever asking, a notice
     * is still gone. Nothing here retries, queues, escalates or rings, and no
     * `urgency` and no `category` changes that.
     *
     * Good for "Waschmaschine fertig" / "Post da" / "Fenster offen". Never for
     * smoke, intrusion, a medical event, or anything else that has to wake
     * somebody — that needs a real alerting transport, which does not exist. A
     * Solaris timer or Wecker is rung by the speaker announcement on the Voice PE
     * satellite, which stays its primary path; this event is the copy for a phone
     * out of earshot. If you are about to hang an alarm on this, stop here.
     */
    const val EVENT_HA = "ha"

    /** The contract's cap: a notice carries at most three actions. */
    const val MAX_NOTICE_ACTIONS = 3

    /**
     * Which notification channel a notice belongs on (#1280). The categories must
     * stay **separately mutable**: whoever mutes the house's notices must not
     * thereby lose a timer, so each gets its own channel rather than a shared one.
     *
     * There is deliberately no `alarm` value — a Wecker arrives as [TIMER], because
     * a category by that name on a best-effort stream reads like a promise this
     * stream does not make (see [EVENT_HA]).
     */
    enum class NoticeCategory(val wire: String, val channelId: String) {
        /** `POST /api/ha/notify` — a household notice from an HA automation. */
        HOUSE("house", "solaris_notice_house"),

        /** The timer scheduler — a fired timer **or** Wecker. */
        TIMER("timer", "solaris_notice_timer"),

        /** The timer scheduler — a fired reminder. */
        REMINDER("reminder", "solaris_notice_reminder"),
        ;

        companion object {
            /**
             * The category a frame names. `category` is **optional and additive**:
             * a v0.46.0-shaped frame carries none and means [HOUSE], and an unknown
             * future value is treated as [HOUSE] too — never dropped, because a
             * notice nobody sees is the worse failure.
             */
            fun of(raw: String?): NoticeCategory {
                val v = raw?.trim()?.lowercase().orEmpty()
                return entries.firstOrNull { it.wire == v } ?: HOUSE
            }
        }
    }

    /**
     * One offered action, exactly as the contract states it since solarisbay#1283
     * (shipped v0.48.0): `{entity_id, service, title, confirm}` — **separate
     * fields**, never one overloaded `domain.suffix` string. [entityId] and
     * [service] together are literally the `/napi/ha/call` body, so the call is
     * passed through **verbatim**; nothing here derives a service from a name or
     * guesses what a string meant. Which of them may become a button is decided in
     * [NoticeActions], never here.
     *
     * [confirm] says whether the receiver must ask before running it. The server
     * computes it from the entity's HA `device_class` (`cover` is a garage door
     * *and* a kitchen blind under one domain — the domain alone cannot tell them
     * apart), it is always on the wire, and a caller may raise it but never lower
     * it. **A missing `confirm` is read as `true`** — see [parseHa].
     */
    data class NoticeAction(
        val entityId: String,
        val service: String,
        val title: String,
        val confirm: Boolean,
    )

    /** A parsed `ha` frame. [urgency] is presentation only — see [EVENT_HA]. */
    data class NoticeEvent(
        val title: String,
        val body: String,
        val category: NoticeCategory,
        val urgency: String,
        val actions: List<NoticeAction>,
        /**
         * What KIND of notice this is (#143) — a **closed** discriminator, not free
         * text the app acts on. Only [KIND_APP_UPDATE] means anything here; every
         * other value, including the empty default, behaves exactly as before.
         *
         * Deliberately NOT accompanied by a URL. A notification that opens an
         * address handed to it is a phishing surface; this field only says *that*
         * something is offered, and the app resolves *where* from the server it is
         * paired with (contract solarisbay#1326).
         */
        val kind: String = "",
    )

    /**
     * Parse an `ha` frame's `data` payload into a [NoticeEvent]. Returns null on
     * malformed JSON or a missing `title` — the caller treats null as "nothing to
     * report", never a crash, exactly like [parseServicebay].
     *
     * Unparseable action entries are dropped individually (a bad button must not
     * cost the notice its text), and the list is capped at [MAX_NOTICE_ACTIONS].
     */
    fun parseHa(data: String?): NoticeEvent? {
        if (data.isNullOrBlank()) return null
        return try {
            noticeOf(JSONObject(data))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The same parse from an already-decoded object — the entry the catch-up
     * backlog ([NoticeBacklog]) uses, because a backlogged notice is the stream's
     * event **verbatim** and there must not be a second renderer that can drift
     * from this one.
     */
    fun noticeOf(o: JSONObject): NoticeEvent? {
        val title = o.optString("title").trim()
        if (title.isBlank()) return null
        return NoticeEvent(
            title = title,
            body = o.optString("body").trim(),
            category = NoticeCategory.of(o.optString("category")),
            urgency = o.optString("urgency").trim().lowercase().ifBlank { "normal" },
            actions = parseNoticeActions(o.optJSONArray("actions")),
            kind = o.optString("kind").trim().lowercase(),
        )
    }

    private fun parseNoticeActions(arr: org.json.JSONArray?): List<NoticeAction> {
        if (arr == null) return emptyList()
        val out = ArrayList<NoticeAction>(MAX_NOTICE_ACTIONS)
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_NOTICE_ACTIONS) break
            val item = arr.optJSONObject(i) ?: continue
            val entityId = item.optString("entity_id").trim()
            val service = item.optString("service").trim()
            val title = item.optString("title").trim()
            // An old one-string action (`{action,title}`) has no entity_id and is
            // dropped here: the notice still shows, only without a button. The
            // server refuses that shape with 400 since v0.48.0, but an older box
            // may still emit it and a swallowed notice is the worse failure.
            if (entityId.isBlank() || service.isBlank() || title.isBlank()) continue
            out.add(NoticeAction(entityId, service, title, confirmOf(item)))
        }
        return out
    }

    /**
     * **A missing `confirm` is `true`.** The single most important line of the
     * #123 contract: the field is computed server-side and always on the wire from
     * v0.48.0 on, so its absence means "an older server, which never weighed this
     * up" — and defaulting that to `false` would eventually put a garage door one
     * tap away on a lock screen. Same rule the repo learned in #84 (`unknown` must
     * never look like `abgeschlossen`) and #120 (a missing card was painted "lamp
     * off"): **the unknown case is never the harmless one.**
     *
     * A value that is present but not a boolean is unknown too, hence `true`.
     */
    private fun confirmOf(item: JSONObject): Boolean =
        if (item.isNull("confirm")) true else item.optBoolean("confirm", true)

    /** A parsed `card_state` frame: which entity, and its fresh card. */
    data class CardStateEvent(val entityId: String, val card: Card)

    /** A parsed `servicebay` approval frame. */
    data class ApprovalEvent(val id: String, val kind: String, val summary: String)

    /**
     * Parse a `servicebay` frame's `data` payload (`{"id","kind","summary"}`) into
     * an [ApprovalEvent]. Returns null on malformed JSON or a missing `id` — the
     * caller treats null as "nothing to notify", never a crash.
     */
    fun parseServicebay(data: String?): ApprovalEvent? {
        if (data.isNullOrBlank()) return null
        return try {
            val o = JSONObject(data)
            val id = o.optString("id")
            if (id.isBlank()) return null
            ApprovalEvent(id, o.optString("kind"), o.optString("summary"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a `card_state` frame's `data` payload
     * (`{"entity_id": "...", "card": { …card shape… }}`) into a [CardStateEvent].
     * Returns null on malformed JSON, a missing/blank `entity_id`, or a missing
     * `card` object — the caller treats null as "nothing to do", never a crash.
     */
    fun parseCardState(data: String?): CardStateEvent? {
        if (data.isNullOrBlank()) return null
        return try {
            val root = JSONObject(data)
            val entityId = root.optString("entity_id")
                .ifBlank { root.optJSONObject("card")?.optString("entity_id") ?: "" }
            if (entityId.isBlank()) return null
            val cardObj = root.optJSONObject("card") ?: return null
            CardStateEvent(entityId, ApiClient.parseCardJson(cardObj))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Exponential reconnect backoff (#48): `base * 2^attempt`, capped at [cap].
     * `attempt` is 0-based (the first retry after a drop). E.g. 2s→4s→8s→…→cap.
     * A [special404] backoff (default 5 min) is returned for the "endpoint not
     * deployed yet" case (solarisbay#806 pending) so we don't spam a 404 loop.
     */
    fun backoffMillis(
        attempt: Int,
        baseMs: Long = 2_000L,
        capMs: Long = 60_000L,
    ): Long {
        if (attempt < 0) return baseMs
        // Guard the shift against overflow for large attempt counts.
        if (attempt >= 32) return capMs
        val grown = baseMs shl attempt
        return if (grown <= 0L || grown > capMs) capMs else grown
    }

    /** Long backoff for HTTP 404 (endpoint not live yet) — avoid spamming logs. */
    const val NOT_DEPLOYED_BACKOFF_MS = 5 * 60 * 1000L

    /**
     * A connection must stay open at least this long to count as **healthy** — only
     * then do we reset the reconnect backoff (#79). Resetting on every `onOpen`
     * let a server that accepts-then-immediately-closes the stream (`getrennt (200)`)
     * drive a ~2s reconnect storm that never backed off; requiring a stable session
     * makes a flapping stream escalate 2s→4s→…→cap instead of hammering the battery.
     */
    const val STABLE_SESSION_MS = 20_000L

    /**
     * True when a session that opened at [connectedAtMs] (0 = never opened) has been
     * up long enough at [nowMs] to be considered healthy — i.e. its drop should
     * reset the backoff. Pure so the churn guard is unit-testable.
     */
    fun healthySession(connectedAtMs: Long, nowMs: Long): Boolean =
        connectedAtMs != 0L && nowMs - connectedAtMs >= STABLE_SESSION_MS
}
