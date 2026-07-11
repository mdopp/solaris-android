package cloud.dopp.solaris.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cloud.dopp.solaris.R
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.Device
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.pair.PairingActivity
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

        if (!TokenStore.isPaired(this)) {
            findViewById<TextView>(R.id.config_status).setText(R.string.widget_config_pair_first)
            findViewById<Button>(R.id.config_pair).apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent(context, PairingActivity::class.java)) }
            }
            return
        }
        loadDevices()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from pairing — retry if now paired.
        if (TokenStore.isPaired(this) &&
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
        val labels = devices.map { "${it.name}  ·  ${it.room ?: it.domain}" }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, pos, _ -> pick(devices[pos]) }
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
