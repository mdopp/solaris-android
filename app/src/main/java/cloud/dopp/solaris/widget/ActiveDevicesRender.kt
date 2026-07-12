package cloud.dopp.solaris.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.Card
import java.util.Locale

/**
 * Size-adaptive [RemoteViews] for the active-devices overview widget (#28).
 * Small = a compact count ("3 an"); large = a header count + a real scrollable
 * ListView collection (backed by [ActiveDevicesRemoteViewsService]) so **every**
 * active device shows and scrolls — the ListView adapter/rows are wired in the
 * provider, this object only builds the header + empty state + the small tier.
 * Pure enough (state labels + tier pick) to be JVM-tested.
 */
object ActiveDevicesRender {

    /** The two tiers: a compact count vs. the scrollable list. */
    enum class Tier { SMALL, LARGE }

    /** Large once the host box is tall/wide enough to fit the scrollable list. */
    fun sizeTier(minW: Int, minH: Int): Tier =
        if (minW >= 180 && minH >= 180) Tier.LARGE else Tier.SMALL

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
     * Large-tier scaffold: just the header count + empty-view visibility. The row
     * list itself is a ListView collection wired in the provider (setRemoteAdapter
     * + notifyAppWidgetViewDataChanged), so this no longer paints per-device rows.
     * [active] null → loading ("…"); empty → show the "Alles aus" empty view.
     */
    fun large(ctx: Context, active: List<Card>?): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_active_large)
        val count = active?.size ?: 0
        v.setTextViewText(
            R.id.av_count,
            if (active == null) "…" else ctx.getString(R.string.active_count, count),
        )
        // Empty view is driven by setEmptyView on the ListView; keep it hidden here so
        // the loading/populated states don't flash it. (The list shows it when empty.)
        v.setViewVisibility(R.id.av_empty, View.GONE)
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
}
