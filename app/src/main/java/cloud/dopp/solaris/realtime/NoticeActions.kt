package cloud.dopp.solaris.realtime

/**
 * Which of a notice's offered actions (#116, contract #123) the app is willing to
 * **run**, and which it refuses quietly.
 *
 * A notification is the first surface in this app that can carry a button which
 * acts on the household, and a notification lies on the **lock screen**. So the
 * question is not "can we map the payload onto a call" but "what may a payload
 * that arrived over the network reach".
 *
 * ## The shape is now stated, not guessed
 *
 * Until solarisbay#1283 an action was **one opaque string**, and `lock.front_door`
 * (an entity) and `cover.close` (a service) are syntactically identical — the
 * server's own tests carried one of each. Guessing wrong turns "show me the front
 * door" into "switch the front door" from the lock screen, so #116 refused
 * everything but a light or a switch.
 *
 * Since v0.48.0 the payload says it outright: `entity_id` and `service` in
 * separate fields, the service's domain equal to the entity's, and the pair is
 * exactly the `/napi/ha/call` body. So the call is **passed through verbatim** —
 * this object derives nothing and guesses nothing; it only decides whether the
 * pair is representable at all.
 *
 * ## What may be named
 *
 * [ACTIONABLE_DOMAINS] mirrors the server's closed set: `light`, `switch`,
 * `cover`, `climate`. **`lock` and `alarm_control_panel` are absent and must stay
 * absent** — the server refuses them in two places (`_parse_actions` and
 * `event_data`), and this is the third. An unlock is *unrepresentable* from a
 * notification, not merely discouraged: the lock chooser (#92) needs the render
 * cache's latch bit and its own warning row, and a notification cannot carry that
 * dialog — so the answer is "not from here", never "a simpler dialog".
 *
 * ## `confirm` is information, not our lock
 *
 * [RealtimeProtocol.NoticeAction.confirm] decides whether the button asks first
 * (via the existing [cloud.dopp.solaris.widget.WidgetActionActivity] dialog from
 * #113), and a **missing** one counts as `true` — see `RealtimeProtocol`. It does
 * **not** replace the lock-screen boundary from #116: activity `PendingIntent`
 * rather than broadcast, `setAuthenticationRequired(true)`, no `showWhenLocked`.
 * Two independent locks; one that relies on the other is none.
 *
 * Pure (no Android) so the reachable set is JVM-asserted, both ways: what a notice
 * may run, and what it may not.
 */
object NoticeActions {

    /**
     * The domains a notice action may name — the server's `ACTIONABLE_DOMAINS`,
     * mirrored. `lock` and `alarm_control_panel` are deliberately missing; adding
     * either here would put a front door on a lock screen.
     */
    val ACTIONABLE_DOMAINS = setOf("light", "switch", "cover", "climate")

    /**
     * Is [raw] shaped like an HA dotted pair — `domain.suffix`, exactly one dot,
     * no whitespace, neither half empty? Both `entity_id` and `service` wear this
     * shape, and a value that passes here is the very string the call is made
     * against.
     */
    fun isDotted(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return false
        val dot = s.indexOf('.')
        return dot > 0 && dot == s.lastIndexOf('.') && dot < s.length - 1
    }

    /** The domain half of a dotted pair, e.g. `cover.garagentor` → `cover`. */
    fun domainOf(raw: String?): String = raw?.trim()?.substringBefore('.', "").orEmpty()

    /**
     * May this pair become a notification button that acts? Three conditions, all
     * read from the payload and none inferred: both halves are dotted, the
     * service's domain **equals** the entity's (the contract's own rule — a
     * `light.toggle` aimed at a cover is a malformed action, not a translation
     * job), and that domain is in [ACTIONABLE_DOMAINS].
     */
    fun runnable(entityId: String?, service: String?): Boolean {
        if (!isDotted(entityId) || !isDotted(service)) return false
        val domain = domainOf(entityId)
        return domain == domainOf(service) && domain in ACTIONABLE_DOMAINS
    }

    /** As [runnable], for a parsed action. */
    fun runnable(action: RealtimeProtocol.NoticeAction): Boolean =
        runnable(action.entityId, action.service)

    /** The subset of [actions] that becomes buttons — possibly none. */
    fun runnable(actions: List<RealtimeProtocol.NoticeAction>): List<RealtimeProtocol.NoticeAction> =
        actions.filter { runnable(it) }.take(RealtimeProtocol.MAX_NOTICE_ACTIONS)

    /** The entity id an action names, trimmed — passed on unchanged. */
    fun entityId(action: RealtimeProtocol.NoticeAction): String = action.entityId.trim()

    /** The service an action names, trimmed — passed on unchanged. */
    fun service(action: RealtimeProtocol.NoticeAction): String = action.service.trim()
}
