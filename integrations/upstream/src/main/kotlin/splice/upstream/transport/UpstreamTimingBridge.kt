// NEW: V4-456 — carries attempt timing across Ktor's OkHttp conversion without changing the wire. Since Oct 26, 2026 it
// carries the attempt's marks, which tell the vendor's route the same milestones the timing hears.
package splice.upstream.transport

import io.ktor.util.AttributeKey
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Per-client handoff. Ktor exposes no request-tag transfer, so an internal header carries a nonce
 *  only as far as this application interceptor. It becomes a typed tag and is removed before any
 *  network interceptor or socket write. The caller releases registrations on every exit. */
internal class UpstreamTimingBridge : Interceptor, EventListener.Factory {
    private val pending = ConcurrentHashMap<String, AttemptMarks>()

    fun register(marks: AttemptMarks): String {
        val token = UUID.randomUUID().toString()
        pending[token] = marks
        return token
    }

    fun release(token: String) {
        pending.remove(token)
    }

    override fun create(call: Call): EventListener {
        val token = call.request().header(UPSTREAM_TIMING_HEADER)
        val marks = token?.let(pending::get) ?: return EventListener.NONE
        return HeaderEvents(marks)
    }

    private class HeaderEvents(private val marks: AttemptMarks) : EventListener() {
        override fun responseHeadersStart(call: Call) {
            marks.headersStarted()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = request.header(UPSTREAM_TIMING_HEADER)
        val marks = token?.let(pending::remove) ?: return chain.proceed(request)
        val marked = request.newBuilder().removeHeader(UPSTREAM_TIMING_HEADER)
            .tag<AttemptMarks>(marks).build()
        return chain.proceed(marked)
    }
}

internal val upstreamTimingBridgeKey = AttributeKey<UpstreamTimingBridge>("splice-upstream-timing")
internal const val UPSTREAM_TIMING_HEADER = "x-splice-upstream-timing"
