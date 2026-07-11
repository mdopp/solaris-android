package cloud.dopp.solaris.data

/**
 * A renderable device card — mirrors solarisbay's `card_spec`
 * (engine/tools/ha.py): the minimal live state of one HA entity.
 */
data class Card(
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String?,
    val state: String?,
    val unit: String?,
) {
    /** Best-effort "active" reading across the supported domains. */
    val isOn: Boolean
        get() = when (state) {
            "on", "open", "opening", "heat", "cool", "auto", "heat_cool" -> true
            else -> false
        }

    /** A cover whose open is safety-gated (garage/door/gate) — needs confirm. */
    val isSensitiveCover: Boolean
        get() = domain == "cover" && deviceClass in SENSITIVE_COVER_CLASSES
}

/** One pickable actuator in the widget configuration screen. */
data class Device(
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String?,
    val room: String?,
)

/** Covers whose open is confirm-first, matching the server's gate. */
val SENSITIVE_COVER_CLASSES = setOf("garage", "door", "gate")
