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
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import cloud.dopp.solaris.realtime.RealtimeService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisApp
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.widget.PwaLauncher
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

    // Realtime toggle (#48): after the user grants notifications, actually enable.
    private val notifPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { _ -> enableRealtime() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_home)
            findViewById<Button>(R.id.qr_scan_btn).setOnClickListener { startScan() }
            findViewById<Button>(R.id.connect_btn).setOnClickListener { onConnect() }
            findViewById<TextView>(R.id.request_access_btn).setOnClickListener { requestAccess() }
            // Quick-add tiles (#37): tap → pin that widget type directly. The tiles
            // are now THE add surface (the standalone add-widget button is gone).
            findViewById<View>(R.id.tile_device).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.DeviceWidgetProvider::class.java)
            }
            // Energy has 3 variants → the tile opens a small chooser so every
            // energy widget type stays reachable.
            findViewById<View>(R.id.tile_energy).setOnClickListener { pinEnergyChoice() }
            findViewById<View>(R.id.tile_active).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.ActiveDevicesWidgetProvider::class.java)
            }
            findViewById<View>(R.id.tile_camera).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.CameraWidgetProvider::class.java)
            }
            findViewById<View>(R.id.tile_voice).setOnClickListener {
                pinTile(cloud.dopp.solaris.widget.VoiceWidgetProvider::class.java)
            }
            findViewById<Button>(R.id.install_pwa_btn).setOnClickListener { installPwa() }
            findViewById<TextView>(R.id.install_pwa_dismiss).setOnClickListener { dismissPwaHint() }
            findViewById<Button>(R.id.open_btn).setOnClickListener { openSolaris() }
            findViewById<Button>(R.id.logout_btn).setOnClickListener { logout() }
            findViewById<Switch>(R.id.realtime_switch)
                .setOnClickListener { onRealtimeToggled((it as Switch).isChecked) }
            findViewById<TextView>(R.id.realtime_interval).setOnClickListener { pickPollInterval() }
            findViewById<TextView>(R.id.app_version).text =
                getString(R.string.app_version_fmt, appVersionName())
            handleDeepLink(intent)
            render()
            // If Live-Updates is on and we're paired, make sure the service runs.
            RealtimeService.ensure(this)
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
        // Only prefill an already-configured server; a fresh install stays blank so
        // the generic layout hint ("https://dein-server") shows — multi-tenant, no
        // hardcoded chat.dopp.cloud (#52).
        if (urlField.text.isNullOrBlank()) {
            ServerStore.baseUrl(this)?.let { urlField.setText(it) }
        }
        findViewById<TextView>(R.id.connect_hint).text =
            if (ServerStore.isConfigured(this) && !TokenStore.isPaired(this)) {
                getString(R.string.home_reconnect, ServerStore.host(this) ?: "")
            } else {
                getString(R.string.home_connect_hint)
            }
        // Ampel: green dot when connected, muted grey otherwise (#37) — set for
        // both states, outside the connected branch.
        findViewById<View>(R.id.status_dot).background?.setTint(
            getColor(if (connected) R.color.energy_supply else R.color.solaris_text_faint),
        )
        if (connected) {
            findViewById<TextView>(R.id.status).text =
                getString(R.string.home_connected, ServerStore.host(this) ?: "")
            // Reflect the persisted Live-Updates state (#48) — a plain click listener
            // is used elsewhere so this programmatic set doesn't re-fire the toggle.
            findViewById<Switch>(R.id.realtime_switch).isChecked =
                ServerStore.realtimeEnabled(this)
            renderPollInterval()
        }

        // #11: the install-PWA hint card is dismissible — once dismissed it stays
        // hidden on future launches (we can't detect an installed WebAPK).
        findViewById<View>(R.id.install_pwa_card).visibility =
            if (ServerStore.isPwaHintDismissed(this)) View.GONE else View.VISIBLE
    }

    /** Persist the dismiss flag (#11) and hide the install-PWA hint card. */
    private fun dismissPwaHint() {
        ServerStore.dismissPwaHint(this)
        findViewById<View>(R.id.install_pwa_card).visibility = View.GONE
    }

    private fun onConnect() {
        val url = findViewById<EditText>(R.id.server_url).text.toString().trim()
        if (url.isBlank()) {
            toast(getString(R.string.home_enter_url)); return
        }
        ServerStore.setBaseUrl(this, url)
        openPairing()
    }

    /**
     * Request access (#50): a user without an account opens their server's
     * request-access page ([SolarisConfig.PORTAL_PATH]) in a Custom Tab. This is
     * a pre-auth public web flow (ADR 0010) — no token, no /napi. Prefer the
     * URL just typed into the field, fall back to a previously stored server;
     * if neither is present, nudge the user to enter the server address first.
     */
    private fun requestAccess() {
        val typed = findViewById<EditText>(R.id.server_url).text.toString().trim()
        val base = typed.ifBlank { ServerStore.baseUrl(this) ?: "" }.trimEnd('/')
        if (base.isBlank()) {
            toast(getString(R.string.home_enter_url)); return
        }
        openUrl(Uri.parse(base + SolarisConfig.PORTAL_PATH))
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
     * The energy tile (#37) covers three variants — Jetzt / Verlauf / Gesamt —
     * so tapping it opens a small styled chooser (type icon + German name), just
     * like the retired full widget-type picker, then pins the picked provider via
     * requestPinAppWidget. The other four tiles pin their single provider directly.
     */
    private fun pinEnergyChoice() {
        val mgr = pinManagerOrToast() ?: return
        val entries = WidgetTypes.ALL.filter {
            it.provider == cloud.dopp.solaris.widget.EnergyWidgetProvider::class.java ||
                it.provider == cloud.dopp.solaris.widget.EnergyStatsWidgetProvider::class.java ||
                it.provider == cloud.dopp.solaris.widget.EnergyTotalsWidgetProvider::class.java
        }
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

    /**
     * Return the [AppWidgetManager] iff pinning is supported (API 26+ & launcher
     * supports it), else show the manual-add toast and return null. Shared by the
     * quick-add tiles [pinTile] and the energy variant chooser [pinEnergyChoice] (#37).
     */
    private fun pinManagerOrToast(): AppWidgetManager? {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            mgr == null || !mgr.isRequestPinAppWidgetSupported
        ) {
            toast(getString(R.string.home_add_widget_manual))
            return null
        }
        return mgr
    }

    /** Quick-add (#37): pin one specific widget type via requestPinAppWidget. */
    private fun pinTile(provider: Class<*>) {
        val mgr = pinManagerOrToast() ?: return
        mgr.requestPinAppWidget(ComponentName(this, provider), null, null)
    }

    /**
     * Step 2 of onboarding (#11): nudge the user to install the Solaris PWA — that's
     * where chat & push notifications live. Opens the server root in the browser so
     * the site's `beforeinstallprompt` / "add to home screen" affordance appears.
     */
    private fun installPwa() {
        PwaLauncher.open(this, PwaLauncher.Routes.ROOT)
    }

    private fun openSolaris() {
        val base = ServerStore.baseUrl(this) ?: return
        openUrl(Uri.parse("$base/"))
    }

    private fun logout() {
        // Stop the realtime service and forget the opt-in — we're unpaired now (#48).
        RealtimeService.stop(this)
        RealtimeService.cancelRescue(this)
        ServerStore.setRealtimeEnabled(this, false)
        TokenStore.clear(this)
        ServerStore.clear(this)
        findViewById<EditText>(R.id.server_url).setText("")
        render()
        toast(getString(R.string.home_logged_out))
    }

    /**
     * Live-Updates switch (#48). Enabling: on Android 13+ first ensure the
     * POST_NOTIFICATIONS grant (the foreground service needs a visible notification),
     * then persist + start the service and prompt the battery-optimisation exemption.
     * Disabling: persist off + stop the service.
     */
    private fun onRealtimeToggled(enabled: Boolean) {
        if (!enabled) {
            ServerStore.setRealtimeEnabled(this, false)
            RealtimeService.stop(this)
            RealtimeService.cancelRescue(this)
            renderPollInterval()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // The result callback finishes the enable (grant or not, we proceed —
            // the notification just won't show if denied, the stream still runs).
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        enableRealtime()
    }

    private fun enableRealtime() {
        ServerStore.setRealtimeEnabled(this, true)
        RealtimeService.start(this)
        // Periodic backstop: revive the service if an OEM kills it (#48).
        RealtimeService.scheduleRescue(this)
        // Keep the switch visually in sync (a denied prompt can leave it toggled).
        runCatching { findViewById<Switch>(R.id.realtime_switch).isChecked = true }
        renderPollInterval()
        promptBatteryExemption()
    }

    /** Human label for a screen-off interval in seconds (0=off, <60=sec, else min). */
    private fun intervalLabel(seconds: Int): String = when {
        seconds <= 0 -> getString(R.string.realtime_interval_off)
        seconds < 60 -> getString(R.string.realtime_interval_sec, seconds)
        else -> getString(R.string.realtime_interval_label, seconds / 60)
    }

    /** Show the screen-off responsiveness (only while Live-Updates is on). */
    private fun renderPollInterval() {
        val tv = findViewById<TextView>(R.id.realtime_interval)
        if (!ServerStore.realtimeEnabled(this)) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        tv.text = intervalLabel(ServerStore.pollSeconds(this))
    }

    /** Pick the screen-off responsiveness (0=off; <60s stays live; ≥60s polls). */
    private fun pickPollInterval() {
        val seconds = intArrayOf(0, 1, 5, 10, 30, 60, 120, 240, 600, 1800, 3600)
        val labels = seconds.map { intervalLabel(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.realtime_interval_title)
            .setItems(labels) { _, which ->
                ServerStore.setPollSeconds(this, seconds[which])
                // Re-evaluate now so a change takes effect promptly (awake or asleep).
                if (ServerStore.realtimeEnabled(this)) RealtimeService.start(this)
                renderPollInterval()
            }
            .setNegativeButton(R.string.home_pick_widget_cancel, null)
            .show()
    }

    /**
     * Ask the OS to exempt Solaris from battery optimisation so the SSE service
     * isn't killed (#48). Guarded/try-catch — some OEMs/ROMs don't expose the
     * intent, and it must never crash the toggle.
     */
    private fun promptBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) return
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            // Fall back to the general battery-settings screen, else give up silently.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
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
