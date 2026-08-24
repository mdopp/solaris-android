package cloud.dopp.solaris.widget

import cloud.dopp.solaris.data.ToolDef
import cloud.dopp.solaris.data.ToolCellSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * One rendered row of a generic tool widget (#70): the item's fields already
 * resolved through the tool's `tool-cell-schema` into the fixed slots the
 * RemoteViews row layout owns. Everything is a display string — the row layout
 * has no formatting logic of its own.
 */
data class ToolCell(
    /** The item's own id, when it carries one — the row tap/action key. */
    val id: String,
    /** Primary line — a row without one is never emitted. */
    val title: String,
    val subtitle: String?,
    /** The `meta` fields joined into a single muted line. */
    val meta: String?,
    /** Status chip (`badge`/`state`). */
    val badge: String?,
    /** Action ids declared for this row (see [ToolCells] on why they're inert). */
    val actions: List<String> = emptyList(),
)

/**
 * The **schema→cell mapper** (#70, ADR 0011): turns a tool's raw item JSON into
 * [ToolCell]s using only the role→field mapping the server declared. This is what
 * makes the widget generic — no per-tool code, no field names baked into the app.
 *
 * Pure (`org.json` only, no Android types) so every shipped schema is covered by
 * JVM tests. Degrading, never guessing: a field the item doesn't carry, a value
 * of a type outside the closed set (text / number / boolean / list), or a role
 * outside the vocabulary is **skipped** — a malformed plugin loses a line, it
 * doesn't crash the home screen.
 *
 * Action ids ride along on the cell but are not yet wired to buttons: the catalog
 * declares *which* action, not which params it needs, so a generic renderer
 * cannot build the `/napi/action-callback` body (solarisbay#1214).
 */
object ToolCells {

    /** Separator between the muted `meta` fields on the detail line. */
    const val META_SEP = " · "

    /** At most this many meta fields fit one row before it gets unreadable. */
    const val META_MAX = 3

    /** A list-valued field renders at most this many entries. */
    const val LIST_MAX = 4

    /** Map every item through [map], dropping the ones that yield no title. */
    fun mapAll(def: ToolDef, rows: List<JSONObject>): List<ToolCell> =
        rows.mapNotNull { map(def.schema, it, def.actions) }

    /**
     * One item → one cell, or null when the schema's `title` field is missing on
     * this item (nothing readable to show, so no row rather than a blank one).
     */
    fun map(
        schema: ToolCellSchema,
        row: JSONObject,
        declaredActions: List<String> = emptyList(),
    ): ToolCell? {
        val title = value(row, schema.title) ?: return null
        val meta = schema.meta
            .mapNotNull { value(row, it) }
            .take(META_MAX)
            .joinToString(META_SEP)
            .ifBlank { null }
        return ToolCell(
            id = idOf(row),
            title = title,
            subtitle = value(row, schema.subtitle),
            meta = meta,
            badge = value(row, schema.badge),
            actions = schema.actions.filter { it in declaredActions },
        )
    }

    /**
     * Read [field] off [row] and format it for display. Covers the closed field-type
     * set of ADR 0011 — text, number, boolean/state, and a list of them — by the
     * JSON value's own type; anything else (a nested object, an unexpected shape)
     * yields null so the slot is simply left out.
     */
    fun value(row: JSONObject, field: String?): String? {
        if (field.isNullOrBlank()) return null
        if (!row.has(field) || row.isNull(field)) return null
        return when (val v = row.opt(field)) {
            is String -> v.trim().ifBlank { null }
            is Boolean -> if (v) "ja" else "nein"
            is Number -> number(v)
            is JSONArray -> joinList(v)
            else -> null
        }
    }

    /** Item id under any of the usual spellings — "" when the item has none. */
    private fun idOf(row: JSONObject): String {
        for (k in ID_KEYS) {
            val v = row.optString(k).trim()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private val ID_KEYS = listOf("entity_id", "id", "item_id", "uid", "key")

    /** Whole numbers lose the ".0" a JSON double would otherwise show. */
    private fun number(n: Number): String {
        val d = n.toDouble()
        return if (!d.isNaN() && !d.isInfinite() && d == Math.floor(d) &&
            Math.abs(d) < 1e15
        ) {
            d.toLong().toString()
        } else {
            n.toString()
        }
    }

    /** A list field (e.g. a photo's `people`) as a bounded, comma-joined line. */
    private fun joinList(arr: JSONArray): String? {
        val parts = ArrayList<String>(minOf(arr.length(), LIST_MAX))
        for (i in 0 until arr.length()) {
            if (parts.size >= LIST_MAX) break
            when (val v = arr.opt(i)) {
                is String -> v.trim().ifBlank { null }?.let { parts.add(it) }
                is Number -> parts.add(number(v))
                is Boolean -> parts.add(if (v) "ja" else "nein")
                else -> Unit
            }
        }
        return parts.joinToString(", ").ifBlank { null }
    }

    // --- cache codec ---------------------------------------------------------

    /**
     * Serialize mapped cells for the per-instance last-good cache (#46 pattern):
     * the collection factory renders from it, so a fresh draw is instant and a
     * failed refresh keeps the previous rows instead of blanking the widget.
     */
    fun encode(cells: List<ToolCell>): String {
        val arr = JSONArray()
        for (c in cells) {
            val o = JSONObject()
            o.put("i", c.id)
            o.put("t", c.title)
            c.subtitle?.let { o.put("s", it) }
            c.meta?.let { o.put("m", it) }
            c.badge?.let { o.put("b", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Inverse of [encode]. Malformed / absent → empty list. */
    fun decode(json: String?): List<ToolCell> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<ToolCell>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("t").ifBlank { null } ?: continue
                out.add(
                    ToolCell(
                        id = o.optString("i"),
                        title = title,
                        subtitle = o.optString("s").ifBlank { null },
                        meta = o.optString("m").ifBlank { null },
                        badge = o.optString("b").ifBlank { null },
                    ),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
