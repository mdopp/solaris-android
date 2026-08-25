package cloud.dopp.solaris.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ToolDef

/**
 * The two things a 1×1 tool tile can do (#71, archetypes B and C of #69). Both are
 * **pure deep links** into the PWA — no data fetch, no schema render — so the tile
 * costs nothing to draw and works for a `.tool` this build has never heard of.
 */
enum class ToolLaunchMode(
    /** Persisted key — a bound tile must survive an app update. */
    val key: String,
    @StringRes val ctaRes: Int,
    @DrawableRes val iconRes: Int,
) {
    /** **B** — open the tool's create screen (its declared `tool-compose-path`). */
    COMPOSE("compose", R.string.tool_launch_cta_compose, R.drawable.ic_plus),

    /** **C** — open the chat with the tool's card already rendered. */
    CHAT("chat", R.string.tool_launch_cta_chat, R.drawable.ic_services),
    ;

    companion object {
        /** Unknown/absent key → [CHAT]: every tool can serve it, so a tile is never dead. */
        fun fromKey(key: String?): ToolLaunchMode =
            entries.firstOrNull { it.key == key?.trim() } ?: CHAT
    }
}

/** One offerable tile: a tool, a mode, and the route the tap opens. */
data class ToolLaunchTile(
    val toolId: String,
    val label: String,
    val mode: ToolLaunchMode,
    /** Route relative to the server base — feed it straight to [PwaLauncher.open]. */
    val path: String,
)

/**
 * Turns the live `.tool` catalog into the tiles the picker offers (#71). Pure (only
 * resource ids and strings) so which tiles a catalog yields is JVM-tested against
 * the real shipped defs.
 *
 * The asymmetry is the whole point: **every** tool gets a chat tile — the PWA falls
 * back to a generic schema card for one it has no builder for — but a *create* tile
 * is offered only where the def declares `tool-compose-path`. `home` and `energy`
 * are view-only and declare none; synthesising `#/p/home/new` for them would ship a
 * button that silently lands on the start page.
 */
object ToolLaunch {

    /** Every tile the catalog can serve, tool order preserved, compose before chat. */
    fun tiles(defs: List<ToolDef>): List<ToolLaunchTile> {
        val out = ArrayList<ToolLaunchTile>(defs.size * 2)
        for (def in defs) {
            if (def.id.isBlank()) continue
            for (mode in ToolLaunchMode.entries) {
                val path = path(def, mode) ?: continue
                out.add(ToolLaunchTile(def.id, def.label.ifBlank { def.id }, mode, path))
            }
        }
        return out
    }

    /** The route [mode] opens for [def], or null when this tool can't serve it. */
    fun path(def: ToolDef, mode: ToolLaunchMode): String? = when (mode) {
        ToolLaunchMode.COMPOSE -> def.composePath?.let { PwaLauncher.Routes.toolCompose(it) }
        ToolLaunchMode.CHAT -> def.id.ifBlank { null }?.let { PwaLauncher.Routes.toolChat(it) }
    }
}
