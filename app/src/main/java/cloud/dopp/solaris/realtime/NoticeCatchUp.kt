package cloud.dopp.solaris.realtime

import android.content.Context
import cloud.dopp.solaris.SolarisConfig
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
 * The screen-off half of the notice path (#116): during a [RealtimePoll] pass the
 * stream is opened for a few seconds and any `ha` frame that lands in that window
 * is shown through [NoticeNotifier], the same way the live service shows it.
 *
 * ## What this can and cannot recover
 *
 * The server's event bus is in-process pub/sub with **no backlog**
 * (`engine/notify.py`): an event published while nobody is subscribed is not
 * stored, and there is no catch-up endpoint under `/napi/` to ask for missed
 * notices. So this window catches a notice that arrives *while the phone is
 * already awake for its poll* — and nothing else. That is the honest ceiling of
 * the shipped contract, and it is why [RealtimeProtocol.EVENT_HA] says in as many
 * words that this is not an alarm channel. Closing the gap properly needs a
 * server-side "notices since <t>" endpoint, which is a solarisbay change, not one
 * to be faked here.
 *
 * The window costs little: the poll pass has already woken the device and its
 * radio, and [RealtimeIdleReceiver] holds a 30 s wakelock around it. Nor does it
 * cost another surface its copy — while an SSE client is subscribed the server
 * skips the Web Push for that resident (the #715 selective-push rule), but in
 * that case the notice was delivered here instead, natively.
 */
object NoticeCatchUp {

    /**
     * How long a poll pass listens. Comfortably inside the receiver's wakelock and
     * the broadcast's own budget — a longer window would not recover more, since
     * nothing is buffered to recover.
     */
    const val WINDOW_MS = 8_000L

    /**
     * Listen for [WINDOW_MS], post whatever `ha` frames arrive, then close. Blocks
     * the calling thread (the poll already runs off the main thread) and never
     * throws: a backstop poll must not be able to crash the app.
     */
    fun drain(context: Context) {
        val ctx = context.applicationContext
        if (!ServerStore.realtimeEnabled(ctx) || !TokenStore.isPaired(ctx)) return
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
