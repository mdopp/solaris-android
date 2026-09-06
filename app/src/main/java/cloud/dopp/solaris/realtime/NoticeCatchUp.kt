package cloud.dopp.solaris.realtime

import android.content.Context
import cloud.dopp.solaris.SolarisConfig
import cloud.dopp.solaris.data.ApiClient
import cloud.dopp.solaris.data.ServerStore
import cloud.dopp.solaris.data.TokenStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The screen-off half of the notice path (#116, #124). A [RealtimePoll] pass does
 * two things here, in this order, and both end at [NoticeNotifier] so the two
 * paths cannot drift apart in what they show:
 *
 * 1. **Ask what was missed** ([backlog]) — `GET /napi/notifications?since=…`,
 *    the server's short catch-up store (#124).
 * 2. **Listen briefly** ([window]) — the stream is opened for [WINDOW_MS] and
 *    every `ha` frame landing in that window is shown at once.
 *
 * The window stays because it is the **fast** path: a notice published while the
 * phone happens to be awake here arrives immediately rather than at the next
 * wake. The backlog sits beside it, not in its place.
 *
 * ## What this can and cannot recover
 *
 * The event bus itself is in-process pub/sub with no memory, so before #124 a
 * notice published outside the window was simply **gone**. The backlog changed
 * that promise from "can be lost while the screen is off" to **"catches up if the
 * gap is shorter than the server's retention"** — and no further. Past the
 * window, past the server's per-stream row cap, or with nothing ever asking, a
 * notice is still lost, and [RealtimeProtocol.EVENT_HA] still says in as many
 * words that this is not an alarm channel. That caveat is unchanged.
 *
 * The pass costs little: it has already woken the device and its radio, and
 * [RealtimeIdleReceiver] holds a 30 s wakelock around it. Nor does it cost another
 * surface its copy — while an SSE client is subscribed the server skips the Web
 * Push for that resident (the #715 selective-push rule), but in that case the
 * notice was delivered here instead, natively.
 */
object NoticeCatchUp {

    /**
     * How long a poll pass listens on the stream. Comfortably inside the
     * receiver's wakelock and the broadcast's own budget; anything older than the
     * window is the backlog's job, not a longer listen's.
     */
    const val WINDOW_MS = 8_000L

    /**
     * One screen-off pass: the backlog first, then the live window. Blocks the
     * calling thread (the poll already runs off the main thread) and never throws
     * — a backstop poll must not be able to crash the app.
     */
    fun drain(context: Context) {
        val ctx = context.applicationContext
        if (!ServerStore.realtimeEnabled(ctx) || !TokenStore.isPaired(ctx)) return
        runCatching { backlog(ctx) }
        window(ctx)
    }

    /**
     * Ask the server what this device missed and post it, oldest first.
     *
     * Everything that matters here is [NoticeBacklog]'s, which is why it is pure:
     * the next cursor comes from the response's `now` and never from the device
     * clock, `retention_hours` is read rather than assumed, an already-shown
     * notice is dropped by id (or by content, for one the live stream showed),
     * and a first run without a cursor is bounded instead of replaying the whole
     * window.
     *
     * A missing/failed response leaves the cursor untouched: a window we never
     * saw must not be skipped.
     */
    fun backlog(context: Context) {
        val ctx = context.applicationContext
        val since = NoticeSeen.since(ctx)
        // Every exit writes a line (#153). This path used to end in `return` at
        // three places and mapped 404, 401, a timeout and "nothing pending" onto
        // the same silence — so a failing catch-up was indistinguishable from a
        // working one with nothing to do, and cost two rounds of guessing at the
        // wrong cause. The diagnostics screen exists for exactly this (#110).
        val fetch = runCatching { ApiClient(ctx).notificationsFetch(since) }
            .getOrElse { ApiClient.Fetch(0, null) }
        val body = fetch.body
        if (body == null) {
            RealtimeLog.add(
                ctx,
                if (fetch.code == 0) "Nachholen: keine Antwort" else "Nachholen: HTTP ${fetch.code}",
            )
            return
        }
        val result = NoticeBacklog.parse(body, since, NoticeSeen.keys(ctx))
        if (result == null) {
            RealtimeLog.add(ctx, "Nachholen: Antwort unlesbar")
            return
        }
        if (result.show.isEmpty()) {
            // "The server had nothing" and "we filtered it all away" are different
            // findings and used to read the same (#155) — which cost a round.
            RealtimeLog.add(
                ctx,
                if (result.alreadySeen > 0) "Nachholen: ${result.alreadySeen} schon gezeigt"
                else "Nachholen: nichts offen (seit ${since ?: "—"})",
            )
        } else {
            RealtimeLog.add(ctx, "nachgeholt: ${result.show.size}")
        }
        result.show.forEach { item ->
            runCatching { NoticeNotifier.post(ctx, item.event, item.id) }
        }
        NoticeSeen.setSince(ctx, result.nextSince)
    }

    /**
     * Listen for [WINDOW_MS], post whatever `ha` frames arrive, then close. Never
     * throws.
     */
    fun window(context: Context) {
        val ctx = context.applicationContext
        val base = ServerStore.baseUrl(ctx)?.trimEnd('/') ?: return
        val token = TokenStore.get(ctx) ?: return
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val req = Request.Builder()
            .url(base + SolarisConfig.NAPI + "/portal/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()
        val closed = CountDownLatch(1)
        val source = runCatching {
            EventSources.createFactory(client).newEventSource(
                req,
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        if (type != RealtimeProtocol.EVENT_HA) return
                        // A malformed frame is "nothing to report", never a crash.
                        runCatching {
                            RealtimeProtocol.parseHa(data)?.let { NoticeNotifier.post(ctx, it) }
                        }
                    }

                    override fun onClosed(eventSource: EventSource) = closed.countDown()

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) = closed.countDown()
                },
            )
        }.getOrNull() ?: return
        runCatching { closed.await(WINDOW_MS, TimeUnit.MILLISECONDS) }
        runCatching { source.cancel() }
        // Give the socket back rather than leaving it pooled until the next pass.
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }
}
