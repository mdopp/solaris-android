package cloud.dopp.solaris.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisApp
import cloud.dopp.solaris.realtime.RealtimeLog
import java.io.File

/**
 * A no-frills diagnostics screen (#48): app version + device, the last crash trace
 * (if any, from [SolarisApp.CRASH_FILE]), and the realtime connection log
 * ([RealtimeLog]) — so "app keeps stopping" or reconnect churn is inspectable and
 * shareable without adb. Reached by tapping the version line on the home screen.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        render()
        findViewById<Button>(R.id.diag_copy).setOnClickListener {
            val text = findViewById<TextView>(R.id.diag_text).text.toString()
            runCatching {
                val cm = getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Solaris Diagnose", text))
            }
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.diag_clear).setOnClickListener {
            RealtimeLog.clear(this)
            runCatching { File(filesDir, SolarisApp.CRASH_FILE).delete() }
            render()
        }
    }

    private fun render() {
        findViewById<TextView>(R.id.diag_text).text = buildText()
    }

    private fun buildText(): String {
        val sb = StringBuilder()
        sb.append("Version ").append(appVersion())
            .append("  ·  ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append("  ·  Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n")
        readCrash()?.let { sb.append("── Letzter Absturz ──\n").append(it.trim()).append("\n\n") }
        sb.append("── Verbindungs-Log ──\n")
        val log = RealtimeLog.formatted(this)
        sb.append(if (log.isBlank()) "(noch nichts)" else log)
        return sb.toString()
    }

    private fun readCrash(): String? = runCatching {
        val f = File(filesDir, SolarisApp.CRASH_FILE)
        if (f.exists()) f.readText() else null
    }.getOrNull()

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
