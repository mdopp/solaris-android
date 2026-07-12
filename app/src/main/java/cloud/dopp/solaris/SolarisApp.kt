package cloud.dopp.solaris

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a global uncaught-exception handler that writes the stack trace to
 * `filesDir/last_crash.txt` before the process dies. The next time the app opens,
 * [cloud.dopp.solaris.ui.OnboardingHomeActivity] shows it (copyable) — so a crash
 * surfaces as a readable in-app message instead of a silent "app keeps stopping",
 * and no adb/logcat is needed to diagnose it.
 */
class SolarisApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText("Solaris crash\n\n$sw")
            } catch (_: Throwable) {
                // never let the crash-logger itself throw
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
