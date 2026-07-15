package cloud.dopp.solaris.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import cloud.dopp.solaris.R
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import cloud.dopp.solaris.ui.ApprovalsActivity
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
    // Diagnostic status shown in the ongoing notification (#48) so we can tell
    // "not connected" (HTTP error) from "connected but no events" (pinning gap).
    @Volatile private var status: String = "Verbinde …"
    @Volatile private var events = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun nowTime(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())

    override fun onBind(intent: Intent?): IBinder? = null

    // Screen-aware (#48): hold the live SSE only while the screen is on; when it's
    // off, drop the connection (battery/Doze) and let the ~N-min backstop poll run.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground on every (re)start; then act on the current screen
        // state — live SSE if on, backstop poll if off.
        startForegroundNotification()
        if (!eligible()) {
            stopSelfClean()
            return START_NOT_STICKY
        }
        stopping.set(false)
        if (isInteractive()) connect() else onScreenOff()
        return START_STICKY
    }

    private fun isInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun onScreenOn() {
        RealtimePoll.cancel(this)
        stopping.set(false)
        // No transient status flip — onOpen will settle it to "Live-Updates aktiv".
        connect()
    }

    private fun onScreenOff() {
        val secs = ServerStore.pollSeconds(this)
        if (secs in 1..59) {
            // Sub-minute responsiveness is only real by keeping the SSE open — an
            // alarm can't fire that fast in Doze. So < 1 min = stay live (more battery).
            RealtimePoll.cancel(this)
            if (source == null) connect() else setStatus("Live-Updates aktiv")
            return
        }
        // 0 or ≥ 1 min: drop the live stream to save battery.
        runCatching { source?.cancel() }
        source = null
        if (secs <= 0) {
            RealtimePoll.cancel(this)
            setStatus("Ruhemodus · Bildschirm aus")
        } else {
            setStatus("Ruhemodus · prüft alle ${secs / 60} Min")
            RealtimePoll.schedule(this)
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
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
        // No "Verbinde …" flip here — a quick reconnect shouldn't disturb the calm
        // "Live-Updates aktiv" notification. onOpen confirms; a sustained outage flags.
        source = EventSources.createFactory(client).newEventSource(req, listener)
    }

    /**
     * Surface a connection problem on the ongoing notification — but only after a
     * SUSTAINED outage (a few failed retries), so brief blips/reconnects don't
     * disturb the otherwise-silent "active" state. When healthy nothing shows.
     */
    private fun flagDisconnect() {
        if (attempt >= 2) setStatus("Keine Verbindung zu Solaris – neuer Versuch …")
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            attempt = 0 // healthy connection resets the backoff
            setStatus("Live-Updates aktiv")
            // Connection healthy → register the device-widget watch-set (#48
            // Option B, solarisbay#810) and keep re-posting to refresh its TTL.
            // Off-thread + best-effort; a failed post never affects the service.
            scheduleWatchPost()
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            // A bad frame must never crash the service.
            when (type) {
                RealtimeProtocol.EVENT_CARD_STATE -> runCatching {
                    val ev = RealtimeProtocol.parseCardState(data) ?: return
                    DeviceWidgetProvider.wakeEntity(applicationContext, ev.entityId, ev.card)
                    // A state flip can change the active roster → nudge the overview widgets.
                    ActiveDevicesWidgetProvider.refreshAll(applicationContext)
                    events++
                    // Deliberately NO per-event notification update — the ongoing
                    // notification stays calm ("Live-Updates aktiv"); constant re-posts
                    // are unhealthy. State transitions still update it.
                }
                RealtimeProtocol.EVENT_SERVICEBAY -> runCatching {
                    // A new ServiceBay approval (#43) → alert the admin. The verdict
                    // itself happens in the PWA (needs the Authelia session).
                    val ev = RealtimeProtocol.parseServicebay(data) ?: return
                    postApprovalNotif(ev)
                }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            flagDisconnect()
            scheduleReconnect(RealtimeProtocol.backoffMillis(attempt++))
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            when (response?.code) {
                401 -> {
                    // Token invalid/revoked — don't loop; stop until re-paired/re-enabled.
                    setStatus("Token abgelehnt (401)")
                    stopSelfClean()
                    return
                }
                404 -> {
                    // Endpoint not deployed yet (solarisbay#806) — back off long, don't spam.
                    setStatus("Endpoint fehlt (404) — Server nicht bereit?")
                    scheduleReconnect(RealtimeProtocol.NOT_DEPLOYED_BACKOFF_MS)
                    return
                }
            }
            flagDisconnect()
            scheduleReconnect(RealtimeProtocol.backoffMillis(attempt++))
        }
    }

    /**
     * (Re)start the periodic watch-set post (#48): register the current set now,
     * then re-post every [WATCH_REPOST_MS] to refresh the server's TTL as long as
     * the service stays eligible/running. Idempotent — clears any prior schedule
     * first (a reconnect just re-arms it). Cancelled in onDestroy/stopSelfClean.
     */
    private fun scheduleWatchPost() {
        handler.removeCallbacks(watchPoster)
        WatchSet.postCurrentAsync(applicationContext) // register immediately
        handler.postDelayed(watchPoster, WATCH_REPOST_MS)
    }

    private val watchPoster = object : Runnable {
        override fun run() {
            if (stopping.get() || !eligible()) return
            WatchSet.postCurrentAsync(applicationContext)
            handler.postDelayed(this, WATCH_REPOST_MS)
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
        RealtimePoll.cancel(this)
        stopForegroundCompat()
        stopSelf()
    }

    private fun buildNotif(): Notification {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, OnboardingHomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.appName))
            .setContentText(status)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setContentIntent(tap)
            .setShowWhen(false)
            .build()
    }

    /**
     * Post a heads-up notification for a new ServiceBay approval (#43). Tapping it
     * opens the in-app approvals list; the actual approve/reject happens in the PWA
     * (Authelia session). Uses a separate DEFAULT-importance channel so it alerts,
     * unlike the silent ongoing service notification. No-op without POST_NOTIFICATIONS
     * (API 33+); never crashes the service.
     */
    private fun postApprovalNotif(ev: RealtimeProtocol.ApprovalEvent) {
        ensureApprovalChannel()
        val tap = PendingIntent.getActivity(
            this, ev.id.hashCode(),
            Intent(this, ApprovalsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = ev.summary.ifBlank { getString(R.string.approval_notif_generic) }
        val notif = androidx.core.app.NotificationCompat.Builder(this, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.approval_notif_title))
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(APPROVAL_NOTIF_BASE + (ev.id.hashCode() and 0xffff), notif)
        }
    }

    private fun ensureApprovalChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(APPROVAL_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            APPROVAL_CHANNEL_ID,
            getString(R.string.approval_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.approval_channel_desc) }
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundNotification() {
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** Update the ongoing notification's text so the user (and we) can see live state. */
    /**
     * Update the ongoing notification — but only when the text actually CHANGES, so
     * the notification isn't re-posted on every incoming event (that churn is what
     * we want to avoid). Called on real state transitions, not per event.
     */
    private fun setStatus(text: String) {
        if (text == status) return
        status = text
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif())
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

        // Separate DEFAULT-importance channel + id space for approval alerts (#43).
        private const val APPROVAL_CHANNEL_ID = "solaris_approvals"
        private const val APPROVAL_NOTIF_BASE = 4900

        /** Re-post the watch-set every ~20 min to refresh the server-side TTL (#48). */
        private const val WATCH_REPOST_MS = 20L * 60L * 1000L

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
            RealtimePoll.cancel(ctx)
            runCatching { ctx.stopService(Intent(ctx, RealtimeService::class.java)) }
        }

        private const val RESCUE_WORK = "solaris_realtime_rescue"

        /**
         * Schedule the periodic rescue worker (#48) — a WorkManager backstop that
         * revives the service after an OEM kill. Unique + KEEP so repeated calls are
         * idempotent; survives reboots. Called when Live-Updates is enabled.
         */
        fun scheduleRescue(context: Context) {
            val req = androidx.work.PeriodicWorkRequestBuilder<RealtimeRescueWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES,
            ).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build(),
            ).build()
            runCatching {
                androidx.work.WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(
                        RESCUE_WORK, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req,
                    )
            }
        }

        /** Cancel the rescue worker — called when Live-Updates is disabled. */
        fun cancelRescue(context: Context) {
            runCatching {
                androidx.work.WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(RESCUE_WORK)
            }
        }
    }
}
