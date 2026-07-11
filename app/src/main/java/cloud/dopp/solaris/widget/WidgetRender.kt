package cloud.dopp.solaris.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Card

/**
 * Builds the size-adaptive [RemoteViews] for one device widget instance.
 * Small = name + state; medium (wider/taller) = + a detail line. Larger tiers
 * (brightness %, 48h history) arrive with solarisbay#754 / #755.
 */
object WidgetRender {
    private const val ON = 0xFFFFC107.toInt()   // amber = active
    private const val OFF = 0xFF9E9E9E.toInt()  // grey = inactive

    fun build(
        ctx: Context,
        appWidgetId: Int,
        card: Card?,
        fallbackName: String,
        onTap: PendingIntent,
    ): RemoteViews {
        val opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(appWidgetId)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val medium = minW >= 180 && minH >= 110

        val layout = if (medium) R.layout.widget_device_medium else R.layout.widget_device_small
        val v = RemoteViews(ctx.packageName, layout)

        val name = (card?.name ?: fallbackName).ifBlank { fallbackName.ifBlank { "—" } }
        v.setTextViewText(R.id.w_name, name)
        v.setTextViewText(R.id.w_state, stateLabel(card))
        v.setTextColor(R.id.w_state, if (card?.isOn == true) ON else OFF)
        if (medium) v.setTextViewText(R.id.w_detail, detailLine(card))
        v.setOnClickPendingIntent(R.id.w_root, onTap)
        return v
    }

    private fun stateLabel(card: Card?): String {
        if (card == null) return "…"
        return when (card.domain) {
            "cover" -> if (card.isOn) "offen" else "zu"
            "light", "switch" -> if (card.isOn) "an" else "aus"
            else -> card.state ?: "?"
        }
    }

    private fun detailLine(card: Card?): String {
        card ?: return ""
        return if (card.state != null && card.unit != null) "${card.state} ${card.unit}"
        else card.entityId
    }
}
