package cloud.dopp.solaris.data

import cloud.dopp.solaris.SolarisConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry of the server's **`.tool` catalog** (`GET /napi/defs/tool`, #70/#72,
 * solarisbay#1021 + ADR 0011). A tool describes itself declaratively — where its
 * items live, which fields fill which cell role — so a brand-new `.tool` plugin
 * becomes a home-screen widget with **no native code and no app rebuild**.
 *
 * [apiPath] is already normalised to the native surface: the def declares an
 * `/api/…` path (the browser twin), the device-token twins live under `/napi/…`
 * — see [ToolDefs.napiPath].
 */
data class ToolDef(
    /** `tool-id` — the stable key a widget instance is bound to (e.g. `task`). */
    val id: String,
    /** `tool-label` — the German display name (e.g. "Aufgabe"). */
    val label: String,
    /** Item-list path **relative to the `/napi` prefix**, or null when the tool has none. */
    val apiPath: String?,
    /** Optional search path, same normalisation as [apiPath]. */
    val searchPath: String?,
    /** `tool-actions` — the declared action ids (plain strings, ADR 0011). */
    val actions: List<String>,
    /** `tool-cell-schema` — the role→field mapping the row renderer reads. */
    val schema: ToolCellSchema,
) {
    /**
     * Can this tool be offered as a **list widget** (archetype A)?
     *
     * Two hard requirements, both seen in the shipped defs: it must have items to
     * fetch (`note` declares no `tool-api-path`) and its schema must name a
     * `title` field (`energy`/`home` ship an empty `{}` schema — they are bespoke
     * browser cards, not list rows). Offering either would pin a permanently
     * blank widget, so they are excluded from the picker instead.
     */
    val isListable: Boolean
        get() = apiPath != null && schema.title != null
}

/**
 * The **closed** cell-role vocabulary of `tool-cell-schema` (ADR 0011). A value is
 * always a plain field name (or, for [actions], a declared action id) — never
 * markup. Roles outside this vocabulary are browser-only and are skipped, not
 * guessed, so an unknown key can never crash the widget.
 */
data class ToolCellSchema(
    /** Primary line. */
    val title: String? = null,
    /** Secondary line. */
    val subtitle: String? = null,
    /** Muted detail fields, joined into one line. */
    val meta: List<String> = emptyList(),
    /** Small status chip — the `badge` role, or its `state` alias. */
    val badge: String? = null,
    /** Leading glyph field. */
    val icon: String? = null,
    /** Tap targets: ids that must exist in the def's `tool-actions`. */
    val actions: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = title == null && subtitle == null && meta.isEmpty() &&
            badge == null && icon == null && actions.isEmpty()

    companion object {
        val EMPTY = ToolCellSchema()
    }
}

/**
 * Parsing for the `.tool` catalog and for a tool's item payload. Pure — only
 * `org.json`, no Android types — so the whole discovery path (catalog shape,
 * `/api/`→`/napi/` swap, schema vocabulary, item extraction) is JVM-unit-tested
 * without an emulator.
 */
object ToolDefs {

    /**
     * Rewrite a def's declared data path onto the **native** surface, returning it
     * relative to the `/napi` prefix (what [ApiClient] appends to the base URL).
     *
     * The defs declare the browser twin — `tool-api-path: /api/portal/tasks?done=1`
     * — but the device token only authenticates on the proxy-bypassed `/napi/`
     * prefix (solarisbay#757), where the same handlers are re-registered. So
     * `/api/portal/tasks?done=1` → `/portal/tasks?done=1` (i.e. the app calls
     * `<base>/napi/portal/tasks?done=1`). An already-native or bare path is kept
     * as-is; an absolute URL points off our server and is refused (null) rather
     * than sent the device token.
     */
    fun napiPath(declared: String?): String? {
        val raw = declared?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if ("://" in raw) return null
        val p = if (raw.startsWith("/")) raw else "/$raw"
        val stripped = when {
            p == API_PREFIX || p.startsWith("$API_PREFIX/") -> p.removePrefix(API_PREFIX)
            p == SolarisConfig.NAPI || p.startsWith("${SolarisConfig.NAPI}/") ->
                p.removePrefix(SolarisConfig.NAPI)
            else -> p
        }
        return stripped.ifBlank { null }
    }

    /** The full server path a tool's items are fetched from, e.g. `/napi/portal/tasks`. */
    fun fullNapiPath(declared: String?): String? =
        napiPath(declared)?.let { SolarisConfig.NAPI + it }

    /**
     * Parse `GET /napi/defs/tool` → `{ok, kind:"tool", defs:[…]}` into [ToolDef]s,
     * keeping the server's order (it sorts by name). A bare array is accepted too.
     * Malformed input → empty list; a single unparseable def is skipped, never
     * fatal (one bad plugin must not take the whole picker down).
     */
    fun parseCatalog(body: String?): List<ToolDef> {
        if (body.isNullOrBlank()) return emptyList()
        return try {
            val t = body.trim()
            val root =
                if (t.startsWith("[")) JSONObject().put("defs", JSONArray(t)) else JSONObject(t)
            val arr = root.optJSONArray("defs") ?: root.optJSONArray("tools") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseDef(it) } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One catalog row → [ToolDef], or null when it carries no usable `tool-id`. */
    fun parseDef(o: JSONObject): ToolDef? {
        val id = (o.optString("tool-id").ifBlank { o.optString("id") }).trim()
        if (id.isEmpty()) return null
        val label = o.optString("tool-label").ifBlank { o.optString("name") }.ifBlank { id }
        val actions = stringList(o.opt("tool-actions"))
        val schema = parseSchema(o.optJSONObject("tool-cell-schema"))
        return ToolDef(
            id = id,
            label = label,
            apiPath = napiPath(o.optString("tool-api-path")),
            searchPath = napiPath(o.optString("tool-search-path")),
            actions = actions,
            // The schema may only reference ids the def actually declares — an
            // action id we can't dispatch is dropped rather than shown dead.
            schema = schema.copy(actions = schema.actions.filter { it in actions }),
        )
    }

    /**
     * Read the closed role vocabulary out of a `tool-cell-schema` object. Unknown
     * roles are ignored; a role whose value has the wrong shape (an object where a
     * field name belongs, a number, …) degrades to "absent". An empty/absent
     * schema yields [ToolCellSchema.EMPTY] — the marker that this tool is a
     * bespoke browser card, not a list.
     */
    fun parseSchema(o: JSONObject?): ToolCellSchema {
        if (o == null) return ToolCellSchema.EMPTY
        return ToolCellSchema(
            title = single(o, "title"),
            subtitle = single(o, "subtitle"),
            meta = stringList(o.opt("meta")),
            // `badge` and `state` are the two spellings of the same chip role.
            badge = single(o, "badge") ?: single(o, "state"),
            icon = single(o, "icon"),
            actions = stringList(o.opt("actions")),
        )
    }

    /**
     * Pull the item rows out of a tool's data payload. The shipped endpoints wrap
     * their list under a handler-specific key (`tasks`, `contacts`, `documents`,
     * …), so we look for the known names first and otherwise take the payload's
     * first array — a new tool with a new key still renders. A bare array works
     * too. Bounded by [cap]; malformed → empty.
     */
    fun rows(body: String?, cap: Int = MAX_ROWS): List<JSONObject> {
        if (body.isNullOrBlank()) return emptyList()
        return try {
            val t = body.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else firstArray(JSONObject(t))
            if (arr == null) return emptyList()
            val out = ArrayList<JSONObject>(minOf(arr.length(), cap))
            for (i in 0 until arr.length()) {
                if (out.size >= cap) break
                arr.optJSONObject(i)?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Body for `POST /napi/action-callback` (solarisbay `action_callback`):
     * `{"action_id": …, "params": {…}}`, plus `confirmed` on the retry after the
     * server's 403 confirm-gate. Pure so the wire shape is unit-testable.
     *
     * NB: which **params** an action needs is action-specific (`task.set_status`
     * wants `entity_id`+`status`) and the catalog does not declare them yet, so a
     * generic renderer cannot fill them — see solarisbay#1214. Until then this
     * builder exists for callers that know their params.
     */
    fun actionBody(actionId: String, params: JSONObject?, confirmed: Boolean = false): JSONObject {
        val o = JSONObject().put("action_id", actionId)
        o.put("params", params ?: JSONObject())
        if (confirmed) o.put("confirmed", true)
        return o
    }

    /** The browser-side prefix a def declares its paths under. */
    private const val API_PREFIX = "/api"

    /** Hard cap on rendered rows — a RemoteViews collection stays bounded. */
    const val MAX_ROWS = 40

    /** Payload keys known to carry a tool's item list, tried before the fallback. */
    private val ROW_KEYS = listOf(
        "items", "results", "rows", "data", "entries", "list",
        "tasks", "contacts", "documents", "photos", "notes", "cards",
    )

    private fun firstArray(root: JSONObject): JSONArray? {
        for (k in ROW_KEYS) root.optJSONArray(k)?.let { return it }
        val keys = root.keys()
        while (keys.hasNext()) {
            (root.opt(keys.next()) as? JSONArray)?.let { return it }
        }
        return null
    }

    /** A single-field role: a plain string, or the first entry of a list. */
    private fun single(o: JSONObject, key: String): String? = when (val v = o.opt(key)) {
        is String -> v.trim().ifBlank { null }
        is JSONArray -> stringList(v).firstOrNull()
        else -> null
    }

    /** A list-valued role: an array of strings, or one bare string. Blanks dropped. */
    private fun stringList(v: Any?): List<String> = when (v) {
        is String -> listOfNotNull(v.trim().ifBlank { null })
        is JSONArray -> (0 until v.length())
            .mapNotNull { (v.opt(it) as? String)?.trim()?.ifBlank { null } }
        else -> emptyList()
    }
}
