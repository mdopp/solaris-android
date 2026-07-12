package cloud.dopp.solaris.data

import kotlin.math.roundToInt

/**
 * A renderable device card — mirrors solarisbay's `card_spec`
 * (engine/tools/ha.py): the live state of one HA entity, incl. the #754
 * control attributes (brightness / cover position / climate temperature).
 */
data class Card(
    val entityId: String,
    val name: String,
    val domain: String,
    val deviceClass: String?,
    val state: String?,
    val unit: String?,
    val brightness: Int? = null,   // 0-255 (light)
    val position: Int? = null,     // 0-100 (cover)
    val temperature: Double? = null, // climate current temperature
) {
    /** Best-effort "active" reading across the supported domains. */
    val isOn: Boolean
        get() = when (state) {
            "on", "open", "opening", "heat", "cool", "auto", "heat_cool" -> true
            else -> false
        }

    /** Light brightness as a percentage (0-100), or null. */
    val brightnessPct: Int?
        get() = brightness?.let { (it / 255.0 * 100).roundToInt().coerceIn(0, 100) }

    /**
     * A 0-100 "level" for the magnitude bar: brightness for lights, position for
     * covers, else null (no bar).
     */
    val level: Int?
        get() = when (domain) {
            "light" -> if (isOn) brightnessPct ?: 100 else 0
            "cover" -> position
            else -> null
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

/**
 * Is a device (by [domain] + [deviceClass]) safety-gated — i.e. its action needs
 * a confirmation (#38)? Mirrors [Card.isSensitiveCover] but works from the bound
 * widget metadata when no live [Card] is available yet (cache/loading render).
 */
fun isSensitiveDevice(domain: String?, deviceClass: String?): Boolean =
    domain == "cover" && deviceClass in SENSITIVE_COVER_CLASSES
