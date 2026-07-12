package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Energy
import cloud.dopp.solaris.data.EnergyFlow
import java.util.Locale
import kotlin.math.abs

/**
 * Size-adaptive rendering of the house energy widget.
 * Small = grid net (Bezug/Einspeisung); large = the PV/Haus/Netz/Akku flow.
 */
object EnergyRender {
    private const val IMPORT = 0xFFEF5350.toInt()  // red = drawing from grid / charging
    private const val EXPORT = 0xFF66BB6A.toInt()  // green = PV / feed-in / discharging
    private const val NEUTRAL = 0xFFB0BEC5.toInt()

    fun build(
        ctx: Context,
        appWidgetId: Int,
        energy: Energy?,
        paired: Boolean,
        onTap: PendingIntent,
    ): RemoteViews {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val large = minW >= 180 && minH >= 110

        val v = RemoteViews(ctx.packageName, if (large) R.layout.widget_energy_large else R.layout.widget_energy_small)
        v.setOnClickPendingIntent(R.id.e_root, onTap)

        val primary = if (large) R.id.e_grid else R.id.e_main
        if (!paired) {
            if (large) hideRows(v)
            v.setTextViewText(primary, "koppeln")
            v.setTextColor(primary, NEUTRAL)
            return v
        }
        if (energy == null) {
            if (large) hideRows(v)
            v.setTextViewText(primary, "…")
            v.setTextColor(primary, NEUTRAL)
            return v
        }
        if (large) renderLarge(v, energy) else renderSmall(v, energy)
        return v
    }

    private fun renderSmall(v: RemoteViews, e: Energy) {
        val g = e.grid ?: e.house
        val w = g?.watts
        if (w == null) {
            v.setTextViewText(R.id.e_title, "Energie")
            v.setTextViewText(R.id.e_main, "—")
            v.setTextColor(R.id.e_main, NEUTRAL)
            return
        }
        val (dir, color) = when {
            g.sense == "grid" && w >= 0 -> "Netz · Bezug" to IMPORT
            g.sense == "grid" -> "Netz · Einspeisung" to EXPORT
            else -> "Haus" to NEUTRAL
        }
        v.setTextViewText(R.id.e_title, dir)
        v.setTextViewText(R.id.e_main, fmt(w))
        v.setTextColor(R.id.e_main, color)
    }

    private fun renderLarge(v: RemoteViews, e: Energy) {
        val maxW = listOf(e.pv, e.house, e.grid, e.battery)
            .mapNotNull { it?.watts?.let { w -> abs(w) } }
            .maxOrNull() ?: 1.0
        leg(v, R.id.e_pv, R.id.e_pv_bar, e.pv, maxW)
        leg(v, R.id.e_house, R.id.e_house_bar, e.house, maxW)
        leg(v, R.id.e_grid, R.id.e_grid_bar, e.grid, maxW)
        leg(v, R.id.e_battery, R.id.e_battery_bar, e.battery, maxW)
    }

    private fun leg(v: RemoteViews, textId: Int, barId: Int, f: EnergyFlow?, maxW: Double) {
        if (f?.watts == null) {
            v.setViewVisibility(textId, View.GONE)
            v.setViewVisibility(barId, View.GONE)
            return
        }
        val w = f.watts
        val color = when (f.sense) {
            "supply" -> EXPORT
            "grid", "battery" -> if (w >= 0) IMPORT else EXPORT
            else -> NEUTRAL
        }
        val dir = when (f.sense) {
            "grid" -> if (w >= 0) "  · Bezug" else "  · Einspeisung"
            "battery" -> if (w >= 0) "  · lädt" else "  · entlädt"
            else -> ""
        }
        v.setViewVisibility(textId, View.VISIBLE)
        v.setTextViewText(textId, "${f.label}   ${fmt(w)}$dir")
        v.setTextColor(textId, color)
        v.setViewVisibility(barId, View.VISIBLE)
        val pct = (abs(w) / maxW * 100).toInt().coerceIn(0, 100)
        v.setProgressBar(barId, 100, pct, false)
    }

    private fun hideRows(v: RemoteViews) {
        for (id in intArrayOf(R.id.e_pv, R.id.e_house, R.id.e_grid, R.id.e_battery,
                R.id.e_pv_bar, R.id.e_house_bar, R.id.e_grid_bar, R.id.e_battery_bar)) {
            v.setViewVisibility(id, View.GONE)
        }
    }

    private fun fmt(w: Double): String {
        val a = abs(w)
        return if (a >= 1000) String.format(Locale.GERMANY, "%.1f kW", a / 1000.0)
        else String.format(Locale.GERMANY, "%.0f W", a)
    }
}
