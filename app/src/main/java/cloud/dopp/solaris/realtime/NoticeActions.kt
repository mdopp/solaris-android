package cloud.dopp.solaris.realtime

import cloud.dopp.solaris.widget.DeviceShortcuts

/**
 * Which of a notice's offered actions (#116) the app is willing to **run**, and
 * which it only refuses quietly.
 *
 * A notification is the first surface in this app that can carry a button which
 * acts on the household, and a notification lies on the **lock screen**. So the
 * question is not "can we map the payload onto a call" but "what may a string
 * that arrived over the network reach". Two facts decide it:
 *
 * 1. **`action` is opaque.** The server validates only that it is a non-empty
 *    string (`ha_notify._parse_actions`); nothing over there says what it names.
 *    Its own tests use `"lock.front_door"` (an entity id) and `"cover.close"`
 *    (a service) in the same file — the two examples do not even agree with each
 *    other. Inventing a syntax here would be the app deciding, on its own, how a
 *    remote string turns into a household call.
 * 2. **The widget path is the ceiling.** #92/#97/#100 settled which devices a
 *    one-tap surface outside the app may switch at all: [DeviceShortcuts]'s
 *    `DIRECT_DOMAINS` allowlist (`light`, `switch`). A lock gets a chooser, a
 *    cover/alarm gets "open the card", and nothing else acts directly.
 *
 * Hence the rule below: an action is runnable only when it is **entity-id
 * shaped** and its domain is one the shortcut table already lets a tap switch
 * directly. Everything else — a lock, a garage cover, an alarm panel, a service
 * name, a free-form string — yields **no button at all**. The notice itself is
 * still shown; only the shortcut to acting on it is withheld.
 *
 * What is deliberately NOT built here: no way to reach `lock.open`, `lock.unlock`
 * or any other sensitive action from a notification. The lock chooser (#92) needs
 * the render cache's latch bit and its own warning row, and a notification cannot
 * carry that dialog — so the answer is "not from here", not "a simpler dialog".
 *
 * Pure (no Android) so the reachable set is JVM-asserted, both ways: what a
 * notice may run, and what it may not.
 */
object NoticeActions {

    /**
     * Is [raw] shaped like an entity id — `domain.object_id`, one dot, no
     * whitespace? Same shape [DeviceShortcuts.domainOf] reads a domain from, so a
     * string that passes here is the very string the call would be made against.
     */
    fun isEntityId(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return false
        val dot = s.indexOf('.')
        return dot > 0 && dot == s.lastIndexOf('.') && dot < s.length - 1
    }

    /**
     * May this action become a notification button that acts? Only for an
     * entity-id-shaped target whose domain [DeviceShortcuts] lets act directly —
     * i.e. a light or a switch, never a lock, cover, alarm panel or unknown
     * domain.
     */
    fun runnable(action: RealtimeProtocol.NoticeAction): Boolean =
        isEntityId(action.action) &&
            DeviceShortcuts.actsDirectly(DeviceShortcuts.domainOf(action.action.trim()))

    /** The subset of [actions] that becomes buttons — possibly none. */
    fun runnable(actions: List<RealtimeProtocol.NoticeAction>): List<RealtimeProtocol.NoticeAction> =
        actions.filter { runnable(it) }.take(RealtimeProtocol.MAX_NOTICE_ACTIONS)

    /** The entity id a runnable action names, trimmed. */
    fun entityId(action: RealtimeProtocol.NoticeAction): String = action.action.trim()
}
