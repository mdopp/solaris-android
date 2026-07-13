package cloud.dopp.solaris.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.OnboardingHomeActivity
import cloud.dopp.solaris.widget.ActiveDevicesWidgetProvider
import cloud.dopp.solaris.widget.DeviceWidgetProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded realtime foreground service (#48). Holds a single long-lived **SSE**
 * connection to `GET {baseUrl}/napi/portal/events` (Bearer device-token +
 * `Accept: text/event-stream`) and, on each `card_state` frame, wakes the bound
 * device widgets **from the pushed card** — no re-fetch.
 *
 * Opt-in only ([ServerStore.realtimeEnabled], default off): it runs foreground
 * with a low-importance persistent notification. Reconnects with exponential
 * backoff; a **401** stops the service (token gone), a **404** backs off long
 * (endpoint solarisbay#806 not deployed yet) rather than hammering.
 */
class RealtimeService : Service() {

    // A never-timeout client (SSE is long-lived) — read/write timeouts disabled.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS) // keep NAT/proxy paths alive
            .retryOnConnectionFailure(true)
            .build()
    }

    private val stopping = AtomicBoolean(false)
    @Volatile private var source: EventSource? = null
    @Volatile private var attempt = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground on every (re)start; then (re)connect if still eligible.
        startForegroundNotification()
        if (!eligible()) {
            stopSelfClean()
            return START_NOT_STICKY
        }
        stopping.set(false)
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping.set(true)
        handler.removeCallbacksAndMessages(null)
        runCatching { source?.cancel() }
        source = null
        super.onDestroy()
    }

    /** Paired + toggle on — the only condition under which we should be running. */
    private fun eligible(): Boolean =
        ServerStore.realtimeEnabled(this) &&
            ServerStore.isConfigured(this) &&
            TokenStore.isPaired(this)

    private fun connect() {
        if (stopping.get()) return
        val base = ServerStore.baseUrl(this)?.trimEnd('/') ?: run { stopSelfClean(); return }
        val token = TokenStore.get(this) ?: run { stopSelfClean(); return }
        val req = Request.Builder()
            .url(base + SolarisConfig.NAPI + "/portal/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        runCatching { source?.cancel() }
        source = EventSources.createFactory(client).newEventSource(req, listener)
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            attempt = 0 // healthy connection resets the backoff
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (type != RealtimeProtocol.EVENT_CARD_STATE) return
            // A bad frame must never crash the service.
            runCatching {
                val ev = RealtimeProtocol.parseCardState(data) ?: return
                val woke = DeviceWidgetProvider.wakeEntity(applicationContext, ev.entityId, ev.card)
                // A state flip can change the active roster → nudge the overview widgets.
                ActiveDevicesWidgetProvider.refreshAll(applicationContext)
                Unit.also { _ -> woke }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            scheduleReconnect(RealtimeProtocol.backoffMillis(attempt++))
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            when (response?.code) {
                401 -> {
                    // Token invalid/revoked — don't loop; stop until re-paired/re-enabled.
                    stopSelfClean()
                    return
                }
                404 -> {
                    // Endpoint not deployed yet (solarisbay#806) — back off long, don't spam.
                    scheduleReconnect(RealtimeProtocol.NOT_DEPLOYED_BACKOFF_MS)
                    return
                }
            }
            scheduleReconnect(RealtimeProtocol.backoffMillis(attempt++))
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        if (stopping.get()) return
        if (!eligible()) { stopSelfClean(); return }
        handler.postDelayed({ if (!stopping.get()) connect() }, delayMs)
    }

    private fun stopSelfClean() {
        stopping.set(true)
        handler.removeCallbacksAndMessages(null)
        runCatching { source?.cancel() }
        source = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun startForegroundNotification() {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, OnboardingHomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.realtime_notif_title))
            .setContentText(getString(R.string.realtime_notif_text))
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setContentIntent(tap)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.realtime_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.realtime_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "solaris_realtime"
        private const val NOTIF_ID = 4801

        /** Start the service iff opt-in && paired; else make sure it's stopped. */
        fun ensure(context: Context) {
            val ctx = context.applicationContext
            if (ServerStore.realtimeEnabled(ctx) &&
                ServerStore.isConfigured(ctx) &&
                TokenStore.isPaired(ctx)
            ) {
                start(ctx)
            } else {
                stop(ctx)
            }
        }

        fun start(context: Context) {
            val ctx = context.applicationContext
            val i = Intent(ctx, RealtimeService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun stop(context: Context) {
            val ctx = context.applicationContext
            runCatching { ctx.stopService(Intent(ctx, RealtimeService::class.java)) }
        }
    }
}
