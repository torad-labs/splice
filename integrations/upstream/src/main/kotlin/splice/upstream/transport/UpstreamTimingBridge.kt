// NEW: V4-456 — carries attempt timing across Ktor's OkHttp conversion without changing the wire.
package splice.upstream.transport

import io.ktor.util.AttributeKey
import okhttp3.Interceptor
import okhttp3.Response
import splice.core.perf.UpstreamAttemptTiming
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Per-client handoff. Ktor exposes no request-tag transfer, so an internal header carries a nonce
 *  only as far as this application interceptor. It becomes a typed tag and is removed before any
 *  network interceptor or socket write. The caller releases registrations on every exit. */
internal class UpstreamTimingBridge : Interceptor {
    private val pending = ConcurrentHashMap<String, UpstreamAttemptTiming>()

    fun register(timing: UpstreamAttemptTiming): String {
        val token = UUID.randomUUID().toString()
        pending[token] = timing
        return token
    }

    fun release(token: String) {
        pending.remove(token)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = request.header(UPSTREAM_TIMING_HEADER)
        val timing = token?.let(pending::remove) ?: return chain.proceed(request)
        val marked = request.newBuilder().removeHeader(UPSTREAM_TIMING_HEADER)
            .tag<UpstreamAttemptTiming>(timing).build()
        return chain.proceed(marked)
    }
}

internal val upstreamTimingBridgeKey = AttributeKey<UpstreamTimingBridge>("splice-upstream-timing")
internal const val UPSTREAM_TIMING_HEADER = "x-splice-upstream-timing"
