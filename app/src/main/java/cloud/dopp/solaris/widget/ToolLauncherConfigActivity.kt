package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.data.ToolDef
import cloud.dopp.solaris.data.ToolDefs
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import kotlin.concurrent.thread

/**
 * Binds a freshly placed 1×1 launcher tile to one tool **and one mode** (#71).
 *
 * The list is [ToolLaunch.tiles] over the **live catalog** — so a `.tool` installed
 * after this build is offered without an app rebuild, and a tool that declares no
 * `tool-compose-path` is simply missing its "Neu" entry instead of getting a tile
 * that deep-links into the start page. Unlike the list widget's picker there is no
 * `isListable` filter: a launcher needs no items and no cell schema, which is why
 * `.note`, `.home` and `.energy` are offerable here and not there.
 *
 * Re-entered from the launcher's *Einrichten* entry the tile is already bound
 * (#96): the list marks the current tool **and** mode, and a back-out leaves the
 * tile where it is — see [ConfigEntry].
 */
class ToolLauncherConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** The tool + mode this tile already opens, if we were re-opened (#96). */
    private var boundToolId: String? = null
    private var boundMode = ToolLaunchMode.COMPOSE

    private var entry = ConfigEntry.FIRST_PLACEMENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        boundToolId = WidgetStore.toolLaunchId(this, appWidgetId)
        boundMode = WidgetStore.toolLaunchMode(this, appWidgetId)
        entry = ConfigEntry.of(boundToolId != null)
        // Backing out of a reconfigure must not cost the user the tile (#96).
        setResult(entry.backOutResult, resultIntent())
        setContentView(R.layout.activity_widget_config)
        applyStatusBarInset()

        if (!ServerStore.isConfigured(this) || !TokenStore.isPaired(this)) {
            findViewById<TextView>(R.id.config_status).setText(R.string.widget_config_pair_first)
            findViewById<Button>(R.id.config_pair).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    startActivity(Intent(context, OnboardingHomeActivity::class.java))
                }
            }
            return
        }
        loadTiles()
    }

    private fun applyStatusBarInset() {
        val root = findViewById<View>(R.id.config_root)
        val basePad = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = basePad + top)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (ServerStore.isConfigured(this) && TokenStore.isPaired(this) &&
            findViewById<ListView>(R.id.config_list).adapter == null
        ) {
            findViewById<Button>(R.id.config_pair).visibility = View.GONE
            loadTiles()
        }
    }

    private fun loadTiles() {
        val status = findViewById<TextView>(R.id.config_status)
        val list = findViewById<ListView>(R.id.config_list)
        status.setText(R.string.tool_config_loading)
        thread {
            val tiles = ToolLaunch.tiles(loadCatalog())
            runOnUiThread { if (!isFinishing) showTiles(list, status, tiles) }
        }
    }

    /** Fresh catalog when reachable (and remembered), else the last-seen one. */
    private fun loadCatalog(): List<ToolDef> {
        val body = try {
            ApiClient(applicationContext).toolCatalogBody()
        } catch (e: Exception) {
            null
        }
        if (body != null) {
            WidgetStore.setToolCatalogJson(applicationContext, body)
            val fresh = ToolDefs.parseCatalog(body)
            if (fresh.isNotEmpty()) return fresh
        }
        return ToolDefs.parseCatalog(WidgetStore.toolCatalogJson(applicationContext))
    }

    private fun showTiles(list: ListView, status: TextView, tiles: List<ToolLaunchTile>) {
        if (tiles.isEmpty()) {
            status.setText(R.string.tool_launch_empty)
            return
        }
        status.setText(
            if (entry.isReconfigure) R.string.tool_launch_change else R.string.tool_launch_pick,
        )
        list.adapter = TileAdapter(this, tiles, boundToolId, boundMode)
        list.setOnItemClickListener { _, _, pos, _ -> pick(tiles[pos]) }
        // Reconfigure: open on the entry the tile currently uses (#96). Tool AND
        // mode — the same tool appears twice, once per mode.
        tiles.indexOfFirst { it.toolId == boundToolId && it.mode == boundMode }
            .takeIf { it >= 0 }?.let { list.setSelection(it) }
    }

    private class TileAdapter(
        private val ctx: Context,
        private val tiles: List<ToolLaunchTile>,
        /** The tile's current binding, marked in the list on reconfigure (#96). */
        private val currentToolId: String?,
        private val currentMode: ToolLaunchMode,
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(ctx)

        override fun getCount() = tiles.size
        override fun getItem(position: Int) = tiles[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: inflater.inflate(R.layout.item_widget_type, parent, false)
            val tile = tiles[position]
            v.findViewById<ImageView>(R.id.type_icon).setImageResource(tile.mode.iconRes)
            val label = ctx.getString(
                R.string.tool_launch_entry, tile.label, ctx.getString(tile.mode.ctaRes),
            )
            v.findViewById<TextView>(R.id.type_label).text =
                if (tile.toolId == currentToolId && tile.mode == currentMode) {
                    ctx.getString(R.string.widget_config_current, label)
                } else {
                    label
                }
            return v
        }
    }

    private fun pick(tile: ToolLaunchTile) {
        // Keyed by appWidgetId: re-binding overwrites all four fields of this
        // instance's entry, never adds a second one (#96).
        WidgetStore.bindToolLaunch(this, appWidgetId, tile)
        ToolLauncherWidgetProvider.requestRefresh(applicationContext, appWidgetId)
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}
