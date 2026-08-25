package cloud.dopp.solaris.widget

import cloud.dopp.solaris.data.ToolDef
import cloud.dopp.solaris.data.ToolCellSchema
import cloud.dopp.solaris.data.ToolDefs
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
    /** Action ids the schema declares for this row. */
    val actions: List<String> = emptyList(),
    /**
     * The one action this row offers as a button (#90), or null when the tool
     * declares none — or when the row lacks a field the action's params need.
     */
    val actionId: String? = null,
    /**
     * [actionId]'s fully resolved `params` object, JSON-encoded — the exact body
     * `POST /napi/action-callback` is sent. Resolved at map time so the button
     * carries everything it needs and the tap does no catalog lookup.
     */
    val actionParams: String? = null,
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
 * Since solarisbay#1214 the catalog also declares **where an action's params come
 * from** (`tool-action-params`), so a row's `/napi/action-callback` body is
 * resolved here — still with no per-tool knowledge — and the row can carry a
 * working button (#90) instead of only opening the web card.
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
        rows.mapNotNull { map(def.schema, it, def.actions, def.actionParams) }

    /**
     * One item → one cell, or null when the schema's `title` field is missing on
     * this item (nothing readable to show, so no row rather than a blank one).
     */
    fun map(
        schema: ToolCellSchema,
        row: JSONObject,
        declaredActions: List<String> = emptyList(),
        actionParams: Map<String, Map<String, String>> = emptyMap(),
    ): ToolCell? {
        val title = value(row, schema.title) ?: return null
        val meta = schema.meta
            .mapNotNull { value(row, it) }
            .take(META_MAX)
            .joinToString(META_SEP)
            .ifBlank { null }
        val actions = schema.actions.filter { it in declaredActions }
        val action = resolveAction(actions, row, actionParams)
        return ToolCell(
            id = idOf(row),
            title = title,
            subtitle = value(row, schema.subtitle),
            meta = meta,
            badge = value(row, schema.badge),
            actions = actions,
            actionId = action?.first,
            actionParams = action?.second?.toString(),
        )
    }

    /**
     * The row's button (#90): the first schema-declared action whose params the
     * catalog declares **and** this row can fill. Everything else yields null —
     * no button rather than one that would 400 on a missing `entity_id`. A tool
     * that declares no `tool-action-params` (today: all but `.task`) therefore
     * behaves exactly as before.
     */
    fun resolveAction(
        actions: List<String>,
        row: JSONObject,
        actionParams: Map<String, Map<String, String>>,
    ): Pair<String, JSONObject>? {
        for (id in actions) {
            val mapping = actionParams[id] ?: continue
            val params = resolveParams(mapping, row) ?: continue
            return id to params
        }
        return null
    }

    /**
     * Resolve one action's `param → source` mapping against [row]: a `$`-prefixed
     * source reads the row field of that name, anything else is a literal
     * (solarisbay `_ACTION_PARAM_FIELD_PREFIX`). Null when a referenced field is
     * missing or carries a value outside the closed type set — a half-filled
     * callback body must never go out.
     *
     * Field values are taken **raw** (the JSON string/number/boolean), not the
     * display formatting: the server wants the item's `id`, not "ja"/"nein".
     */
    fun resolveParams(mapping: Map<String, String>, row: JSONObject): JSONObject? {
        if (mapping.isEmpty()) return null
        val out = JSONObject()
        for ((param, source) in mapping) {
            if (param.isBlank() || source.isBlank()) return null
            if (source.startsWith(ToolDefs.FIELD_PREFIX)) {
                val field = source.removePrefix(ToolDefs.FIELD_PREFIX)
                if (field.isBlank()) return null
                out.put(param, raw(row, field) ?: return null)
            } else {
                out.put(param, source)
            }
        }
        return out
    }

    /** [field]'s raw JSON value, restricted to the closed scalar set; else null. */
    private fun raw(row: JSONObject, field: String): Any? {
        if (!row.has(field) || row.isNull(field)) return null
        return when (val v = row.opt(field)) {
            is String -> v.trim().ifBlank { null }
            is Number, is Boolean -> v
            else -> null
        }
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
            // The row's resolved action rides into the cache with it — the button
            // must work off a cold redraw, without re-reading the catalog.
            c.actionId?.let { o.put("a", it) }
            c.actionParams?.let { o.put("p", it) }
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
                        actionId = o.optString("a").ifBlank { null },
                        actionParams = o.optString("p").ifBlank { null },
                    ),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
