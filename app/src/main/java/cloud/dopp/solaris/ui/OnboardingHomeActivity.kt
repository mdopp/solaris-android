package cloud.dopp.solaris.ui

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisApp
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.widget.WidgetTypes
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

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
        try {
            setContentView(R.layout.activity_home)
            findViewById<Button>(R.id.qr_scan_btn).setOnClickListener { startScan() }
            findViewById<Button>(R.id.connect_btn).setOnClickListener { onConnect() }
            findViewById<Button>(R.id.add_widget_btn).setOnClickListener { addWidget() }
            findViewById<Button>(R.id.open_btn).setOnClickListener { openSolaris() }
            findViewById<Button>(R.id.logout_btn).setOnClickListener { logout() }
            handleDeepLink(intent)
            render()
        } catch (e: Throwable) {
            showError("onCreate", e)
        }
        // A crash in a previous process (e.g. the deep-link launch) was written to
        // file — surface it now as a readable, copyable message.
        showLastCrashIfAny()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            handleDeepLink(intent)
            render()
        } catch (e: Throwable) {
            showError("onNewIntent", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            render()
        } catch (e: Throwable) {
            showError("onResume", e)
        }
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
        openUrl(Uri.parse(base + SolarisConfig.PAIR_PATH))
    }

    /** Open in a Custom Tab, falling back to a plain browser intent, then a toast. */
    private fun openUrl(uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e2: Exception) {
                toast(getString(R.string.home_no_browser))
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != SolarisConfig.PAIR_SCHEME || data.host != SolarisConfig.PAIR_HOST) return
        // Token rides the fragment (cloud.dopp.solaris://pair#token=…). Parse the
        // fragment AND query by hand — never call getQueryParameter (it throws on
        // opaque URIs) — and swallow anything so this path can't crash the app.
        val token = try {
            paramFrom(data.fragment) ?: paramFrom(data.encodedQuery)
        } catch (e: Exception) {
            null
        }
        if (token != null && token.startsWith("sol_device_")) {
            try {
                TokenStore.save(this, token)
                toast(getString(R.string.pairing_success))
            } catch (e: Exception) {
                toast(getString(R.string.pairing_failed))
            }
        } else {
            toast(getString(R.string.pairing_failed))
        }
    }

    /** Pull `token=…` out of a `k=v&k=v` string (URL fragment or query). */
    private fun paramFrom(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val raw = s.split("&")
            .firstOrNull { it.startsWith("${SolarisConfig.PAIR_TOKEN_PARAM}=") }
            ?.substringAfter("=") ?: return null
        val decoded = runCatching { Uri.decode(raw) }.getOrNull() ?: raw
        return decoded.ifBlank { null }
    }

    /**
     * Offer *every* widget type (#34), not just the device widget: a styled list
     * (type icon + German name) → pin the picked provider via requestPinAppWidget.
     * Launchers that don't support pinning get a German toast fallback.
     */
    private fun addWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            mgr == null || !mgr.isRequestPinAppWidgetSupported
        ) {
            toast(getString(R.string.home_add_widget_manual))
            return
        }
        val entries = WidgetTypes.ALL
        val adapter = object : ArrayAdapter<WidgetTypes.Entry>(this, 0, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_widget_type, parent, false)
                val entry = entries[position]
                row.findViewById<ImageView>(R.id.type_icon).setImageResource(entry.icon)
                row.findViewById<TextView>(R.id.type_label).setText(entry.label)
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.home_pick_widget_title)
            .setAdapter(adapter) { _, which ->
                val provider = entries[which].provider
                mgr.requestPinAppWidget(ComponentName(this, provider), null, null)
            }
            .setNegativeButton(R.string.home_pick_widget_cancel, null)
            .show()
    }

    private fun openSolaris() {
        val base = ServerStore.baseUrl(this) ?: return
        openUrl(Uri.parse("$base/"))
    }

    private fun logout() {
        TokenStore.clear(this)
        ServerStore.clear(this)
        findViewById<EditText>(R.id.server_url).setText("")
        render()
        toast(getString(R.string.home_logged_out))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun showError(where: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        // An error in the *current* session — jump straight to the details view
        // (Copy + Melden), no "report last crash?" preamble needed.
        showCrashDetails(sw.toString())
    }

    /**
     * Surface a crash saved by [SolarisApp] from a previous process death, then
     * clear it so it doesn't nag on every launch. Staged: a discreet Stage-1
     * message with [Details]/[Fehler melden]/[Schließen] — the raw trace only
     * appears on request.
     */
    private fun showLastCrashIfAny() {
        val f = File(filesDir, SolarisApp.CRASH_FILE)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        if (!text.isNullOrBlank()) showCrashPrompt(text)
    }

    /** Stage 1 — friendly message; the trace is not shown yet. */
    private fun showCrashPrompt(trace: String) {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(R.string.crash_message)
                .setPositiveButton(R.string.crash_report) { _, _ -> reportCrash(trace) }
                .setNeutralButton(R.string.crash_details) { _, _ -> showCrashDetails(trace) }
                .setNegativeButton(R.string.crash_close, null)
                .show()
        }
    }

    /** Stage 2 — the full stack trace (scrollable), with Copy + Melden. */
    private fun showCrashDetails(trace: String) {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_details_title)
                .setMessage(trace.take(6000))
                .setPositiveButton(R.string.crash_copy) { _, _ -> copyTrace(trace) }
                .setNeutralButton(R.string.crash_report) { _, _ -> reportCrash(trace) }
                .setNegativeButton(R.string.crash_close, null)
                .show()
        }
    }

    private fun copyTrace(trace: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Solaris-Fehler", trace))
        toast(getString(R.string.crash_copied))
    }

    /** Open a prefilled GitHub new-issue form (title+body+label) in a Custom Tab. */
    private fun reportCrash(trace: String) {
        val url = cloud.dopp.solaris.CrashReport.issueUrl(
            trace = trace,
            appVersion = appVersionName(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        openUrl(Uri.parse(url))
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
