package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Energy
import cloud.dopp.solaris.data.EnergyFlow

/**
 * Size-adaptive rendering of the "Jetzt" energy widget.
 * Small = the grid net (Bezug/Einspeisung); large = the 2×2 PV/Haus/Netz/Akku
 * flow grid, styled like the Solaris web Energy "jetzt" screen (colored value +
 * direction per leg).
 */
object EnergyRender {

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

        return if (large) buildGrid(ctx, energy, paired, onTap)
        else buildSmall(ctx, energy, paired, onTap)
    }

    private fun buildSmall(ctx: Context, e: Energy?, paired: Boolean, onTap: PendingIntent): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy_small)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        if (!paired) {
            v.setTextViewText(R.id.e_title, "Energie")
            v.setTextViewText(R.id.e_main, "koppeln")
            v.setTextColor(R.id.e_main, EnergyStyle.NEUTRAL)
            return v
        }
        val g = e?.grid ?: e?.house
        val w = g?.watts
        if (w == null) {
            v.setTextViewText(R.id.e_title, "Energie")
            v.setTextViewText(R.id.e_main, if (e == null) "…" else "—")
            v.setTextColor(R.id.e_main, EnergyStyle.NEUTRAL)
            return v
        }
        val title = when {
            g.sense == "grid" && w >= 0 -> "Netz · Bezug"
            g.sense == "grid" -> "Netz · Einspeisung"
            else -> "Haus"
        }
        v.setTextViewText(R.id.e_title, title)
        v.setTextViewText(R.id.e_main, EnergyStyle.formatWatts(w))
        v.setTextColor(R.id.e_main, EnergyStyle.colorFor(g.sense, w))
        return v
    }

    private fun buildGrid(ctx: Context, e: Energy?, paired: Boolean, onTap: PendingIntent): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_energy_grid)
        v.setOnClickPendingIntent(R.id.e_root, onTap)
        v.setTextViewText(R.id.e_head, "Energie · jetzt")

        val legs = EnergyStyle.orderedLegs(e?.pv, e?.house, e?.grid, e?.battery)
        val fallbackLabels = listOf("PV", "Haus", "Netz", "Akku")
        val note = when {
            !paired -> "koppeln"
            e == null -> "…"
            else -> null
        }
        for (i in 0..3) {
            cell(v, i, legs[i], fallbackLabels[i], note)
        }
        return v
    }

    private fun cell(v: RemoteViews, i: Int, f: EnergyFlow?, fallbackLabel: String, note: String?) {
        val labelId = LABEL_IDS[i]; val valueId = VALUE_IDS[i]; val dirId = DIR_IDS[i]
        v.setTextViewText(labelId, f?.label?.ifBlank { fallbackLabel } ?: fallbackLabel)
        if (note != null) {
            v.setTextViewText(valueId, note)
            v.setTextColor(valueId, EnergyStyle.NEUTRAL)
            v.setTextViewText(dirId, "")
            return
        }
        val w = f?.watts
        v.setTextViewText(valueId, EnergyStyle.formatWatts(w))
        v.setTextColor(valueId, EnergyStyle.colorFor(f?.sense, w))
        v.setTextViewText(dirId, EnergyStyle.directionFor(f?.sense, w))
    }

    private val LABEL_IDS = intArrayOf(R.id.e_l0, R.id.e_l1, R.id.e_l2, R.id.e_l3)
    private val VALUE_IDS = intArrayOf(R.id.e_v0, R.id.e_v1, R.id.e_v2, R.id.e_v3)
    private val DIR_IDS = intArrayOf(R.id.e_d0, R.id.e_d1, R.id.e_d2, R.id.e_d3)
}
