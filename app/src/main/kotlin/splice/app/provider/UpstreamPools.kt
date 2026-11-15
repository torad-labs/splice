// NEW: server mode (docs/specs/server-mode.md, F4). The daemon's pools: one for each provider that lists
// `[[providers.<p>.upstreams]]`, made the first time a head of that provider is built and kept for the daemon's life,
// so every head of the provider balances over the same servers and a head rebuilt while the daemon runs keeps what the
// pool knows. Making a pool starts its health checks: each server is asked `/health` at once and then every
// `health_seconds`, on its own loop, so one server that hangs holds up no other's reading.
//
// It also keeps, for every provider a chat head was built for, pool or not, the headers the daemon's own requests to
// that provider's server carry: server mode relays an OpenAI-style client's requests with them (F4, openai-route).
package splice.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.app.probe.MS_PER_SECOND
import splice.core.pool.UpstreamPool
import splice.core.topology.ProviderConfig
import splice.core.topology.UpstreamConfig
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** The headers a request the daemon itself makes of a provider's server carries (a health check, an OpenAI-style
 *  client's request relayed as sent), read at each request: the provider's static headers and the key its turns
 *  send, so a server behind a gateway answers it as it answers a turn, and a key changed since boot is the one
 *  sent. */
internal fun interface ServerHeaders {
    fun now(): Map<String, String>
}

/** How a health check reaches a server: the JDK client in production, a scripted one in a test. */
internal fun interface HealthHttp {
    fun with(headers: Map<String, String>): LocalHttp
}

internal class UpstreamPools(
    /** The daemon's own scope, which its stop cancels: the health loops end with it. */
    private val scope: CoroutineScope,
    private val log: LogSink,
    private val dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val ticker: Ticker = ProcessTicker(),
    http: HealthHttp? = null,
) {
    private val pools = ConcurrentHashMap<String, UpstreamPool>()
    private val order = ConcurrentHashMap<String, Int>()
    private val carried = ConcurrentHashMap<String, ServerHeaders>()

    // One client for every check: a JDK client holds a selector thread of its own.
    private val client by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HEALTH_CONNECT_SECONDS)).build()
    }
    private val http = http ?: HealthHttp { headers -> JdkLocalHttp(headers, client) }

    /** The pool of the provider [key], or null when it lists no upstreams. The first call for a provider makes its
     *  pool and starts its health checks, which carry [headers]. With a pool or without, the provider's requests
     *  carry the headers of the last call from then. */
    fun of(key: String, provider: ProviderConfig, headers: ServerHeaders): UpstreamPool? {
        carried[key] = headers
        if (provider.upstreams.isEmpty()) return null
        return pools.computeIfAbsent(key) {
            order[key] = order.size
            UpstreamPool(key, provider.upstreams, log).also { pool ->
                val check = HealthCheck(pool, provider.healthSeconds * MS_PER_SECOND, headers)
                pool.upstreams.forEach { watch(check, it, waitFirst = false) }
            }
        }
    }

    /** The pool of the provider [key], when a head of it was built. */
    fun named(key: String): UpstreamPool? = pools[key]

    /** What a request to the provider [key] carries now; nothing for a provider no chat head was built for. */
    fun headers(key: String): Map<String, String> = carried[key]?.now().orEmpty()

    /** Every pool, in the order they were made. */
    fun all(): List<UpstreamPool> = pools.values.sortedBy { order[it.provider] }

    /** One server's loop: a check at once (or, for a loop started again, one interval later), then one every
     *  interval. A loop that died of a failure no check expects would leave its server's reading frozen, up or down,
     *  so the server reads down from then and the loop is started again: supervision owns that class, as in
     *  LocalRuntimeWatch, and the daemon's stop, which cancels the scope, restarts nothing. */
    private fun watch(check: HealthCheck, upstream: UpstreamConfig, waitFirst: Boolean) {
        val loop = scope.launch(dispatcher) {
            if (waitFirst && !ticker.awaitTick(check.everyMs)) return@launch
            while (isActive) {
                ask(check, upstream)
                if (!ticker.awaitTick(check.everyMs)) return@launch
            }
        }
        loop.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) return@invokeOnCompletion
            val why = "its health check stopped (${SafeFailureText.render(cause)})"
            check.pool.checked(upstream.name, healthy = false, said = why)
            if (scope.isActive) watch(check, upstream, waitFirst = true)
        }
    }

    /** One health check of [upstream]. A check that could not be made is read as one that failed. */
    private fun ask(check: HealthCheck, upstream: UpstreamConfig) {
        val asked = Cancellables.runCatchingCancellable {
            http.with(check.headers.now())("GET", upstream.healthUrl, null)
        }
        check.found(upstream, asked)
    }

    /** What every server of one pool is checked with: how often, and the headers a check carries. */
    private class HealthCheck(val pool: UpstreamPool, val everyMs: Long, val headers: ServerHeaders) {
        /** What asking [upstream] for its health came to: healthy on a 2xx, and not on any other answer or none. A
         *  check that could not be made reads the server down and says why in the pool's line. */
        fun found(upstream: UpstreamConfig, asked: Result<LocalHttpReply?>) {
            val status = asked.fold(onSuccess = { it?.status }, onFailure = { null })
            val said = asked.fold(
                onSuccess = { reply -> reply?.let { "HTTP ${it.status}" } ?: "no answer" },
                onFailure = { "the check failed: ${SafeFailureText.render(it)}" },
            )
            pool.checked(upstream.name, status != null && status in OK_FROM until OK_BELOW, said)
        }
    }
}

// why: a server on the team's own network accepts at once; one still connecting after two seconds is read as down,
// the bound the local-runtime probes use (JdkLocalHttp).
private const val HEALTH_CONNECT_SECONDS = 2L

// The statuses that say a server can take a turn, the 2xx: llama-server answers 200 once its model is loaded and 503
// before.
private const val OK_FROM = 200

// The first status past the 2xx: an answer under it, from 200 up, is a healthy server's.
private const val OK_BELOW = 300
