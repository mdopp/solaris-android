package cloud.dopp.solaris.pair

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.TokenStore

/**
 * Device-token pairing screen (Phase 3).
 *
 * - Opened normally: shows the pairing status and a button that launches the
 *   authenticated `/pair-device` page in a Chrome Custom Tab (where the Authelia
 *   cookies live, so a token can be minted).
 * - Reopened via the `cloud.dopp.solaris://pair?token=…` deep link — the redirect
 *   from that page — it captures the `sol_device_…` token and stores it encrypted.
 *
 * The web page is served by solarisbay (tracked in solarisbay#751); until it
 * ships, the deep-link capture can be exercised with:
 *   adb shell am start -a android.intent.action.VIEW \
 *     -d "cloud.dopp.solaris://pair?token=sol_device_XXXX" cloud.dopp.solaris
 */
class PairingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        findViewById<Button>(R.id.pairing_button).setOnClickListener { startPairing() }
        findViewById<Button>(R.id.pairing_unpair).setOnClickListener {
            TokenStore.clear(this)
            render()
        }

        handleDeepLink(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        render()
    }

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != SolarisConfig.PAIR_SCHEME || data.host != SolarisConfig.PAIR_HOST) return
        val token = data.getQueryParameter(SolarisConfig.PAIR_TOKEN_PARAM)
        if (!token.isNullOrBlank() && token.startsWith("sol_device_")) {
            TokenStore.save(this, token)
            toast(getString(R.string.pairing_success))
        } else {
            toast(getString(R.string.pairing_failed))
        }
    }

    private fun startPairing() {
        CustomTabsIntent.Builder().build()
            .launchUrl(this, Uri.parse(SolarisConfig.PAIR_URL))
    }

    private fun render() {
        val paired = TokenStore.isPaired(this)
        findViewById<TextView>(R.id.pairing_status).text =
            getString(if (paired) R.string.pairing_paired else R.string.pairing_unpaired)
        findViewById<Button>(R.id.pairing_unpair).visibility =
            if (paired) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
