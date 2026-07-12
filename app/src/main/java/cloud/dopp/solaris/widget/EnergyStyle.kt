package cloud.dopp.solaris.widget

import cloud.dopp.solaris.data.EnergyFlow
import cloud.dopp.solaris.data.EnergyTotal
import java.util.Locale
import kotlin.math.abs

/**
 * Pure (context-free) energy presentation helpers shared by all three energy
 * widgets — colors, flow direction, and W/kW formatting — mirroring the Solaris
 * web Energy screen. JVM-testable (no Android APIs).
 */
object EnergyStyle {
    const val SUPPLY = 0xFF66BB6A.toInt()   // green: PV / feed-in / discharge
    const val DRAW = 0xFFEF5350.toInt()     // red: consumption / import / charge
    const val NEUTRAL = 0xFFB0BEC5.toInt()
    const val BATTERY = 0xFF7E9CFF.toInt()

    /** The accent color for a flow leg by its sense + sign. */
    fun colorFor(sense: String?, watts: Double?): Int {
        val w = watts ?: return NEUTRAL
        return when (sense) {
            "supply" -> SUPPLY
            "draw" -> DRAW
            "grid" -> if (w >= 0) DRAW else SUPPLY
            "battery" -> if (w >= 0) DRAW else SUPPLY
            else -> NEUTRAL
        }
    }

    /** The human direction suffix (Bezug/Einspeisung/lädt/entlädt), or "". */
    fun directionFor(sense: String?, watts: Double?): String {
        val w = watts ?: return ""
        return when (sense) {
            "supply" -> "liefert"
            "grid" -> if (w >= 0) "Bezug" else "Einspeisung"
            "battery" -> if (w >= 0) "lädt" else "entlädt"
            else -> ""
        }
    }

    /** Line/legend color for a leg in the Verlauf chart. */
    fun seriesColor(sense: String?): Int = when (sense) {
        "supply" -> SUPPLY
        "draw" -> DRAW
        "grid" -> 0xFFFFC107.toInt() // amber, distinct from the +/- grid flow tint
        "battery" -> BATTERY
        else -> NEUTRAL
    }

    fun formatWatts(w: Double?): String {
        if (w == null) return "—"
        val a = abs(w)
        return if (a >= 1000) String.format(Locale.GERMANY, "%.1f kW", a / 1000.0)
        else String.format(Locale.GERMANY, "%.0f W", a)
    }

    fun formatKwh(v: Double?): String {
        if (v == null) return "—"
        return String.format(Locale.GERMANY, "%.1f kWh", v)
    }

    /** The four canonical legs in display order (PV / Haus / Netz / Akku). */
    fun orderedLegs(
        pv: EnergyFlow?, house: EnergyFlow?, grid: EnergyFlow?, battery: EnergyFlow?,
    ): List<EnergyFlow?> = listOf(pv, house, grid, battery)

    /** The four canonical "Gesamt" slots, keyed to server totals. */
    val TOTAL_SLOTS = listOf("pv", "export", "import", "battery")

    /** Fallback labels + colors for the totals grid, in [TOTAL_SLOTS] order. */
    val TOTAL_LABELS = listOf("PV", "Einspeisung", "Netzbezug", "Batterie")
    val TOTAL_COLORS = intArrayOf(SUPPLY, SUPPLY, DRAW, BATTERY)

    /**
     * Map the server `totals` onto the four canonical slots by key/sense synonyms,
     * preserving [TOTAL_SLOTS] order. Non-matching totals are ignored; missing
     * slots are null.
     */
    fun orderedTotals(totals: List<EnergyTotal>): List<EnergyTotal?> =
        TOTAL_SLOTS.map { slot -> totals.firstOrNull { matchesSlot(it.key, slot) } }

    private fun matchesSlot(key: String?, slot: String): Boolean {
        val k = key?.lowercase() ?: return false
        return when (slot) {
            "pv" -> k == "pv" || k == "supply" || k == "solar" || k == "production" || k == "generation"
            "export" -> k == "export" || k == "feed_in" || k == "feedin" || k == "grid_export"
            "import" -> k == "import" || k == "grid" || k == "grid_import" || k == "consumption"
            "battery" -> k == "battery" || k == "battery_charged" || k == "charge"
            else -> false
        }
    }
}
