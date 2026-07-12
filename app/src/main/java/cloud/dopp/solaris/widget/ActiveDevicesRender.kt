package cloud.dopp.solaris.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Card
import java.util.Locale

/**
 * Size-adaptive [RemoteViews] for the active-devices overview widget (#28).
 * Small = a compact count ("3 an"); large = a bounded list of the active
 * devices (type icon + name + state), styled like the picker rows. Pure enough
 * (state labels + tier pick) to be JVM-tested.
 */
object ActiveDevicesRender {

    /** The two tiers: a compact count vs. the row list. */
    enum class Tier { SMALL, LARGE }

    /** Number of concrete row slots in [R.layout.widget_active_large]. */
    const val MAX_ROWS = 12

    /** Approx height (dp) a single list row occupies (icon 22dp + 8dp top gap). */
    private const val ROW_DP = 30

    /** Height (dp) taken by the header + card padding before the first row. */
    private const val HEADER_DP = 56

    /** Large once the host box is tall/wide enough to fit a few rows. */
    fun sizeTier(minW: Int, minH: Int): Tier =
        if (minW >= 180 && minH >= 180) Tier.LARGE else Tier.SMALL

    /**
     * How many device rows to actually fill given the widget's reported min height
     * (#28): compute the rows that fit from [minH] instead of a hard cap, so a tall
     * widget fills and a short one doesn't over-promise. Clamped to the concrete
     * slot count [MAX_ROWS] and to [count] (never show empty trailing slots). We
     * only show "+N weitere" when there are genuinely more actives than fit. Pure
     * so it's JVM-testable.
     */
    fun rowsToShow(minH: Int, count: Int): Int {
        val fit = ((minH - HEADER_DP) / ROW_DP).coerceAtLeast(1)
        return fit.coerceAtMost(MAX_ROWS).coerceAtMost(count)
    }

    private val ROW_IDS = intArrayOf(
        R.id.av_row0, R.id.av_row1, R.id.av_row2, R.id.av_row3, R.id.av_row4, R.id.av_row5,
        R.id.av_row6, R.id.av_row7, R.id.av_row8, R.id.av_row9, R.id.av_row10, R.id.av_row11,
    )
    private val ICON_IDS = intArrayOf(
        R.id.av_row0_icon, R.id.av_row1_icon, R.id.av_row2_icon,
        R.id.av_row3_icon, R.id.av_row4_icon, R.id.av_row5_icon,
        R.id.av_row6_icon, R.id.av_row7_icon, R.id.av_row8_icon,
        R.id.av_row9_icon, R.id.av_row10_icon, R.id.av_row11_icon,
    )
    private val NAME_IDS = intArrayOf(
        R.id.av_row0_name, R.id.av_row1_name, R.id.av_row2_name,
        R.id.av_row3_name, R.id.av_row4_name, R.id.av_row5_name,
        R.id.av_row6_name, R.id.av_row7_name, R.id.av_row8_name,
        R.id.av_row9_name, R.id.av_row10_name, R.id.av_row11_name,
    )
    private val STATE_IDS = intArrayOf(
        R.id.av_row0_state, R.id.av_row1_state, R.id.av_row2_state,
        R.id.av_row3_state, R.id.av_row4_state, R.id.av_row5_state,
        R.id.av_row6_state, R.id.av_row7_state, R.id.av_row8_state,
        R.id.av_row9_state, R.id.av_row10_state, R.id.av_row11_state,
    )

    /** Small-tier scaffold: count + subtitle. [active] null → loading/hint text. */
    fun small(ctx: Context, active: List<Card>?): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_active_small)
        val count = active?.size
        v.setTextViewText(
            R.id.av_count,
            when {
                count == null -> "…"
                count == 0 -> ctx.getString(R.string.active_none)
                else -> ctx.getString(R.string.active_count, count)
            },
        )
        v.setTextViewText(R.id.av_sub, ctx.getString(R.string.active_title))
        return v
    }

    /**
     * Large-tier scaffold: header count + a size-adaptive row list. [minH] is the
     * widget's reported min height (dp); the number of rows shown grows to fill a
     * tall widget instead of a hard cap, so the last row isn't cut when there's
     * room (#28).
     */
    fun large(ctx: Context, active: List<Card>?, minH: Int = 0): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_active_large)
        val count = active?.size ?: 0
        v.setTextViewText(
            R.id.av_count,
            if (active == null) "…" else ctx.getString(R.string.active_count, count),
        )

        // Reset all slots hidden, then fill as many as fit.
        for (id in ROW_IDS) v.setViewVisibility(id, View.GONE)
        v.setViewVisibility(R.id.av_empty, View.GONE)
        v.setViewVisibility(R.id.av_more, View.GONE)

        if (active != null && active.isEmpty()) {
            v.setViewVisibility(R.id.av_empty, View.VISIBLE)
            return v
        }

        val fit = rowsToShow(minH, count)
        val shown = active?.take(fit).orEmpty()
        shown.forEachIndexed { i, card ->
            v.setViewVisibility(ROW_IDS[i], View.VISIBLE)
            v.setImageViewResource(ICON_IDS[i], DeviceIcons.forDomain(card.domain))
            v.setInt(ICON_IDS[i], "setColorFilter", accentFor(card.domain))
            v.setTextViewText(NAME_IDS[i], card.name.ifBlank { card.entityId })
            v.setTextViewText(STATE_IDS[i], stateLabel(card))
        }

        val overflow = count - shown.size
        if (overflow > 0) {
            v.setViewVisibility(R.id.av_more, View.VISIBLE)
            v.setTextViewText(R.id.av_more, ctx.getString(R.string.active_more, overflow))
        }
        return v
    }

    /** Short, German state label for a row — mirrors [WidgetRender.stateLabel]. */
    fun stateLabel(card: Card): String = when (card.domain) {
        "light" -> card.brightnessPct?.let { "an · $it %" } ?: "an"
        "switch" -> "an"
        "cover" -> card.position?.let { p ->
            when {
                p >= 99 -> "offen"
                p <= 1 -> "zu"
                else -> "$p % offen"
            }
        } ?: "offen"
        "climate" -> card.temperature?.let { String.format(Locale.GERMANY, "%.1f °", it) } ?: (card.state ?: "an")
        else -> {
            val s = card.state ?: "an"
            if (!card.unit.isNullOrBlank()) "$s ${card.unit}" else s
        }
    }

    private fun accentFor(domain: String): Int = when (domain) {
        "light" -> 0xFFFFC107.toInt()
        "cover" -> 0xFF7E9CFF.toInt()
        "switch" -> 0xFF66BB6A.toInt()
        "climate" -> 0xFFFF8A65.toInt()
        else -> 0xFF66BB6A.toInt()
    }
}
