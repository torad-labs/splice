// NEW: server mode (docs/specs/server-mode.md, F4). The daemon's pools: one for each provider that lists
// `[[providers.<p>.upstreams]]`, made the first time a head of that provider is built and kept for the daemon's life,
// so every head of the provider balances over the same servers and a head rebuilt while the daemon runs keeps what the
// pool knows. Making a pool starts its checks: each server is asked at once and then at the check's interval, on its
// own loop, so one server that hangs holds up no other's reading. What a check asks is `[server.probe]`'s (PoolCheck):
// `/health` every `health_seconds` by default, or a real completion every `every_seconds`.
//
// It also keeps, for every provider a chat head was built for, pool or not, the headers the daemon's own requests to
// that provider's server carry: server mode relays an OpenAI-style client's requests with them (F4, openai-route).
package splice.app.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.pool.UpstreamPool
import splice.core.topology.ProviderConfig
import splice.core.topology.ServerProbe
import splice.core.topology.UpstreamConfig
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import splice.upstream.readiness.ReadinessReading
import splice.upstream.transport.LocalHttp
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** The headers a request the daemon itself makes of a provider's server carries (a health check, an OpenAI-style
 *  client's request relayed as sent), read at each request: the provider's static headers and the key its turns
 *  send, so a server behind a gateway answers it as it answers a turn, and a key changed since boot is the one
 *  sent. */
internal fun interface ServerHeaders {
    fun now(): Map<String, String>
}

/** How a check reaches a server: the JDK client in production (JdkHealthHttp), a scripted one in a test. [timeout]
 *  bounds how long the server may take to answer; null keeps the bound a health check always had. */
internal fun interface HealthHttp {
    fun with(headers: Map<String, String>, timeout: Duration?): LocalHttp

    /** A client whose requests keep the bound a health check always had. */
    fun with(headers: Map<String, String>): LocalHttp = with(headers, null)
}

internal class UpstreamPools(
    /** The daemon's own scope, which its stop cancels: the check loops end with it. */
    private val scope: CoroutineScope,
    private val log: LogSink,
    private val http: HealthHttp,
    private val dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val ticker: Ticker = ProcessTicker(),
) {
    private val pools = ConcurrentHashMap<String, UpstreamPool>()

    // The most turns each server of a pool takes at once, server mode's `inflight_per_upstream` (#791): 0, any number.
    @Volatile
    private var each = 0
    private val order = ConcurrentHashMap<String, Int>()
    private val carried = ConcurrentHashMap<String, ServerHeaders>()

    /** `[server.probe]` as booted, which every pool made from then is checked by. The daemon sets it before it builds
     *  any head; unset, a pool is checked as server mode always checked one, `/health` with each server up at start. */
    @Volatile
    var probe: ServerProbe = ServerProbe()

    /** Each server of every pool made from now takes at most [most] turns at once, server mode's
     *  `inflight_per_upstream` (#791). The daemon says it as it wires server mode, before any head, and so before any
     *  pool, is built: a pool holds its cap from its first turn, whoever sends it. */
    fun capEach(most: Int) {
        each = most
    }

    /** The pool of the provider [key], or null when it lists no upstreams. The first call for a provider makes its
     *  pool and starts its checks: a health check carries [headers], and a completion probe asks [ready], its
     *  dialect's question. With a pool or without, the provider's requests carry the headers of the last call from
     *  then. */
    fun of(key: String, provider: ProviderConfig, ready: PoolReadiness, headers: ServerHeaders): UpstreamPool? {
        carried[key] = headers
        if (provider.upstreams.isEmpty()) return null
        return pools.computeIfAbsent(key) {
            order[key] = order.size
            val check = PoolCheck.Plan(probe).of(provider.healthSeconds, headers, ready)
            UpstreamPool(key, provider.upstreams, log, cap = each, startHealthy = check.startHealthy).also { pool ->
                pool.upstreams.forEach { watch(Watched(pool, check), it, waitFirst = false) }
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
    private fun watch(watched: Watched, upstream: UpstreamConfig, waitFirst: Boolean) {
        val everyMs = watched.check.everyMs
        val loop = scope.launch(dispatcher) {
            if (waitFirst && !ticker.awaitTick(everyMs)) return@launch
            while (isActive) {
                ask(watched, upstream)
                if (!ticker.awaitTick(everyMs)) return@launch
            }
        }
        loop.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) return@invokeOnCompletion
            val why = "its health check stopped (${SafeFailureText.render(cause)})"
            watched.pool.checked(upstream.name, healthy = false, said = why)
            if (scope.isActive) watch(watched, upstream, waitFirst = true)
        }
    }

    /** One check of [upstream]. A check that could not be made is read as one that failed. */
    private suspend fun ask(watched: Watched, upstream: UpstreamConfig) {
        val reading = Cancellables.runCatchingCancellable { watched.check.ask(http, upstream) }.fold(
            onSuccess = { it },
            onFailure = { ReadinessReading(false, "the check failed: ${SafeFailureText.render(it)}") },
        )
        watched.pool.checked(upstream.name, reading.ready, reading.said)
    }

    /** One pool, and how its servers are checked. */
    private data class Watched(val pool: UpstreamPool, val check: PoolCheck)
}
