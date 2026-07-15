package cloud.dopp.solaris.realtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * Fires the screen-off backstop poll (#48) when the ~4-min alarm goes off. Holds a
 * short wakelock so the network check survives the device going back to sleep, runs
 * [RealtimePoll.run] off the main thread, then releases.
 */
class RealtimeIdleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RealtimePoll.ACTION_POLL) return
        val app = context.applicationContext
        val pending = goAsync()
        val pm = app.getSystemService(PowerManager::class.java)
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "solaris:idlepoll")
        runCatching { wl?.acquire(30_000L) }
        thread {
            try {
                RealtimePoll.run(app)
            } finally {
                runCatching { if (wl?.isHeld == true) wl.release() }
                pending.finish()
            }
        }
    }
}
