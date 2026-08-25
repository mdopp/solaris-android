package cloud.dopp.solaris.widget

import cloud.dopp.solaris.data.isSensitiveDevice

/** What a long-press entry does when it is tapped (#97). */
enum class ShortcutAction {
    /** Switch the device straight away — only where a mistap is harmless. */
    TOGGLE,

    /** Open the lock chooser ([WidgetActionActivity]) — acts on nothing itself. */
    CHOOSE,

    /** Open the device's card in the PWA — no call leaves the phone. */
    OPEN,
}

/**
 * One device the user already put on the home screen: the widget instance plus
 * the meta [WidgetStore] holds for it. Plain data, no Android — the shortcut
 * list is decided from this alone.
 */
data class ShortcutDevice(
    val appWidgetId: Int,
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String? = null,
)

/** One published app-icon shortcut. [id] is stable per entity across refreshes. */
data class ShortcutEntry(
    val id: String,
    val label: String,
    val device: ShortcutDevice,
    val action: ShortcutAction,
) {
    /** Does tapping this entry call a service? Only [ShortcutAction.TOGGLE] does. */
    val actsDirectly: Boolean get() = action == ShortcutAction.TOGGLE
}

/**
 * Which entries the **app-icon long-press** offers (#97).
 *
 * A long-press menu on the *widget* is impossible — that menu belongs to the
 * launcher and takes no app actions (#96) — so the device shortcuts live on the
 * app icon instead. They cannot be static: an XML list written at build time
 * cannot know the user's devices. The source is therefore the [WidgetStore],
 * i.e. exactly the devices the user already chose to put on the home screen.
 *
 * **The cap is asked for, never assumed.** `getMaxShortcutCountPerActivity()` is
 * passed in as [cap]; launchers commonly *show* four but neither the shown nor
 * the allowed number is fixed. With more devices than slots the overflow rule is
 * **most recently added first**: `appWidgetId` is handed out by the
 * `AppWidgetManager` in increasing order, so the newest widget carries the
 * highest id and needs no extra bookkeeping to order by. Deterministic, and it
 * matches the intent — a device just placed is the one being used.
 *
 * **Security (#92 rules, unchanged here):** a lock never gets a directly-acting
 * entry. It gets [ShortcutAction.CHOOSE], which opens the very same chooser as
 * the 1×1 lock tile, behind the device lock and still subject to the server's
 * authoritative 403 `sensitive_action`. `lock.open` is named nowhere in this
 * file: no shortcut carries a service at all, so none can reach the latch. Every
 * other sensitive device (garage/door/gate cover, alarm panel) merely opens its
 * card. Pure (no Android) → JVM-testable, which is the point.
 */
object DeviceShortcuts {

    /** The domains where a mistapped shortcut is harmless, so it may act. */
    val DIRECT_DOMAINS = setOf("light", "switch")

    /** Shortcut-id prefix, so a published entry is recognisable and stable. */
    const val ID_PREFIX = "dev:"

    /** May a shortcut for this domain switch the device without asking? */
    fun actsDirectly(domain: String?, deviceClass: String? = null): Boolean =
        actionFor(domain, deviceClass) == ShortcutAction.TOGGLE

    /**
     * The one rule table. Locks come first: [isSensitiveDevice] would catch them
     * too, but a lock is the one device with a chooser to send the user to.
     */
    fun actionFor(domain: String?, deviceClass: String? = null): ShortcutAction = when {
        domain == "lock" -> ShortcutAction.CHOOSE
        isSensitiveDevice(domain, deviceClass) -> ShortcutAction.OPEN
        domain in DIRECT_DOMAINS -> ShortcutAction.TOGGLE
        else -> ShortcutAction.OPEN
    }

    /**
     * The entries to publish for [devices], never more than [cap].
     *
     * Devices without an entity id are dropped (an unconfigured widget is not a
     * device); a device on two widgets appears once, under its newest widget. A
     * blank name falls back to the entity id — an unnamed row is worse than a
     * technical one.
     */
    fun entries(devices: List<ShortcutDevice>, cap: Int): List<ShortcutEntry> {
        if (cap <= 0) return emptyList()
        return devices
            .filter { it.entityId.isNotBlank() }
            .sortedByDescending { it.appWidgetId } // most recently added first
            .distinctBy { it.entityId }
            .take(cap)
            .map { d ->
                ShortcutEntry(
                    id = ID_PREFIX + d.entityId,
                    label = d.name.ifBlank { d.entityId },
                    device = d,
                    action = actionFor(d.domain, d.deviceClass),
                )
            }
    }
}
