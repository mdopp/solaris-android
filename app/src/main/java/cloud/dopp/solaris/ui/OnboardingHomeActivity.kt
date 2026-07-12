package cloud.dopp.solaris.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.widget.DeviceWidgetProvider

/**
 * The app's launcher + onboarding hub (replaces the retired TWA LauncherActivity
 * and the old PairingActivity). Guides the user in a few clear steps:
 *  1. connect to their own Solaris server (URL entry; QR to follow),
 *  2. install the Solaris PWA (opens the server in a Custom Tab),
 *  3. add widgets (one-tap via requestPinAppWidget).
 * It also captures the `cloud.dopp.solaris://pair#token=…` redirect from the
 * server's /pair-device page and stores the device token.
 */
class OnboardingHomeActivity : AppCompatActivity() {

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScanned(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.qr_scan_btn).setOnClickListener { startScan() }
        findViewById<Button>(R.id.connect_btn).setOnClickListener { onConnect() }
        findViewById<Button>(R.id.add_widget_btn).setOnClickListener { addWidget() }
        findViewById<Button>(R.id.open_btn).setOnClickListener { openSolaris() }
        findViewById<Button>(R.id.logout_btn).setOnClickListener { logout() }

        handleDeepLink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val connected = ServerStore.isConfigured(this) && TokenStore.isPaired(this)
        findViewById<View>(R.id.connect_section).visibility = if (connected) View.GONE else View.VISIBLE
        findViewById<View>(R.id.connected_section).visibility = if (connected) View.VISIBLE else View.GONE

        val urlField = findViewById<EditText>(R.id.server_url)
        if (urlField.text.isNullOrBlank()) {
            urlField.setText(ServerStore.baseUrl(this) ?: SolarisConfig.DEFAULT_SERVER_HINT)
        }
        findViewById<TextView>(R.id.connect_hint).text =
            if (ServerStore.isConfigured(this) && !TokenStore.isPaired(this)) {
                getString(R.string.home_reconnect, ServerStore.host(this) ?: "")
            } else {
                getString(R.string.home_connect_hint)
            }
        if (connected) {
            findViewById<TextView>(R.id.status).text =
                getString(R.string.home_connected, ServerStore.host(this) ?: "")
        }
    }

    private fun onConnect() {
        val url = findViewById<EditText>(R.id.server_url).text.toString().trim()
        if (url.isBlank()) {
            toast(getString(R.string.home_enter_url)); return
        }
        ServerStore.setBaseUrl(this, url)
        openPairing()
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /** Parse `cloud.dopp.solaris://setup?server=…&token=…`, or a plain https URL. */
    private fun handleScanned(text: String) {
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri != null && uri.scheme == SolarisConfig.PAIR_SCHEME && uri.host == SolarisConfig.SETUP_HOST) {
            val server = uri.getQueryParameter(SolarisConfig.SETUP_SERVER_PARAM)
            val token = uri.getQueryParameter(SolarisConfig.PAIR_TOKEN_PARAM)
            if (!server.isNullOrBlank()) {
                ServerStore.setBaseUrl(this, server)
                if (!token.isNullOrBlank() && token.startsWith("sol_device_")) {
                    TokenStore.save(this, token)
                    toast(getString(R.string.pairing_success))
                    render()
                } else {
                    openPairing()
                }
                return
            }
        }
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            ServerStore.setBaseUrl(this, text)
            openPairing()
            return
        }
        toast(getString(R.string.qr_invalid))
    }

    private fun openPairing() {
        val base = ServerStore.baseUrl(this) ?: return
        CustomTabsIntent.Builder().build()
            .launchUrl(this, Uri.parse(base + SolarisConfig.PAIR_PATH))
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != SolarisConfig.PAIR_SCHEME || data.host != SolarisConfig.PAIR_HOST) return
        val token = tokenFromFragment(data.fragment)
            ?: data.getQueryParameter(SolarisConfig.PAIR_TOKEN_PARAM)
        if (!token.isNullOrBlank() && token.startsWith("sol_device_")) {
            TokenStore.save(this, token)
            toast(getString(R.string.pairing_success))
        } else {
            toast(getString(R.string.pairing_failed))
        }
    }

    private fun tokenFromFragment(fragment: String?): String? {
        if (fragment.isNullOrBlank()) return null
        return fragment.split("&")
            .firstOrNull { it.startsWith("${SolarisConfig.PAIR_TOKEN_PARAM}=") }
            ?.substringAfter("=")
            ?.let { Uri.decode(it) }
    }

    private fun addWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr != null && mgr.isRequestPinAppWidgetSupported
        ) {
            mgr.requestPinAppWidget(ComponentName(this, DeviceWidgetProvider::class.java), null, null)
        } else {
            toast(getString(R.string.home_add_widget_manual))
        }
    }

    private fun openSolaris() {
        val base = ServerStore.baseUrl(this) ?: return
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse("$base/"))
    }

    private fun logout() {
        TokenStore.clear(this)
        ServerStore.clear(this)
        findViewById<EditText>(R.id.server_url).setText("")
        render()
        toast(getString(R.string.home_logged_out))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
