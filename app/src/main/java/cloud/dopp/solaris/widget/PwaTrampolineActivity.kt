package cloud.dopp.solaris.widget

import android.app.Activity
import android.os.Bundle

/**
 * Invisible trampoline for a widget body tap (#27): reads the target PWA path from
 * the intent and hands it to [PwaLauncher] (Custom Tab), then finishes. A Custom
 * Tab must be started from an Activity context, so the tap PendingIntent targets
 * this translucent, no-UI shim rather than launching the app shell.
 */
class PwaTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent?.getStringExtra(EXTRA_PATH) ?: PwaLauncher.Routes.ROOT
        PwaLauncher.open(this, path)
        finish()
    }

    companion object {
        const val EXTRA_PATH = "pwa_path"
    }
}
