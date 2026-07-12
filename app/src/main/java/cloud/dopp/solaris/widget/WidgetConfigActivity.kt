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
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.Device
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import kotlin.concurrent.thread

/**
 * Widget configuration screen shown when the widget is placed: lists the
 * household's controllable actuators and binds the chosen one to this instance.
 * If the app isn't paired yet, it points the user to pairing first.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default result: if the user backs out, the host drops the widget.
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContentView(R.layout.activity_widget_config)

        if (!ServerStore.isConfigured(this) || !TokenStore.isPaired(this)) {
            findViewById<TextView>(R.id.config_status).setText(R.string.widget_config_pair_first)
            findViewById<Button>(R.id.config_pair).apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(context, OnboardingHomeActivity::class.java)) }
            }
            return
        }
        loadDevices()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from pairing — retry if now connected.
        if (ServerStore.isConfigured(this) && TokenStore.isPaired(this) &&
            findViewById<ListView>(R.id.config_list).adapter == null
        ) {
            findViewById<Button>(R.id.config_pair).visibility = View.GONE
            loadDevices()
        }
    }

    private fun loadDevices() {
        val status = findViewById<TextView>(R.id.config_status)
        val list = findViewById<ListView>(R.id.config_list)
        status.setText(R.string.widget_config_loading)
        thread {
            val devices = try {
                ApiClient(applicationContext).listAddable()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { showDevices(list, status, devices) }
        }
    }

    private fun showDevices(list: ListView, status: TextView, devices: List<Device>) {
        if (devices.isEmpty()) {
            status.setText(R.string.widget_config_empty)
            return
        }
        status.setText(R.string.widget_config_pick)
        list.adapter = DeviceAdapter(this, devices)
        list.setOnItemClickListener { _, _, pos, _ -> pick(devices[pos]) }
    }

    /** Solaris-styled picker rows: type icon + name + room (replaces simple_list_item_1). */
    private class DeviceAdapter(
        ctx: Context,
        private val devices: List<Device>,
    ) : BaseAdapter() {
        private val inflater = LayoutInflater.from(ctx)

        override fun getCount() = devices.size
        override fun getItem(position: Int) = devices[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: inflater.inflate(R.layout.item_device_row, parent, false)
            val d = devices[position]
            v.findViewById<ImageView>(R.id.row_icon).setImageResource(DeviceIcons.forDomain(d.domain))
            v.findViewById<TextView>(R.id.row_name).text = d.name
            v.findViewById<TextView>(R.id.row_room).text = d.room ?: d.domain
            return v
        }
    }

    private fun pick(d: Device) {
        WidgetStore.bind(this, appWidgetId, d.entityId, d.name, d.domain, d.deviceClass)
        DeviceWidgetProvider.requestRefresh(applicationContext, appWidgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }
}
