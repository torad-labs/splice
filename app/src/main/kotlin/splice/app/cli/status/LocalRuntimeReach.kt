// NEW: V4-415. Whether each local head's runtime answers right now, asked by `splice status`. A local
// head boots even when its runtime is down (ChatArm logs it and turns fail until it is up), so the
// wrapper and the key say nothing about whether a turn would work; the runtime's own answer does.
package splice.app.cli.status

import splice.app.provider.local.JdkHealthHttp
import splice.app.provider.local.LocalProbeInputs
import splice.app.server.HealthHttp
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.upstream.transport.LocalHttp
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Probes every distinct local runtime at once and gives each at most [waitMs]: status is a
 *  command an operator waits on, so a wedged runtime reads as not answering instead of holding it. */
internal class LocalRuntimeReach(
    private val http: LocalHttp? = null,
    private val waitMs: Long = ANSWER_WAIT_MS,
    private val probeInputs: LocalProbeInputs = LocalProbeInputs(),
    private val healthHttp: HealthHttp = JdkHealthHttp(),
) {
    /** Whether [baseUrl] names a loopback address on this machine; remote `local = true` providers sit outside it. */
    fun isLoopback(baseUrl: String): Boolean = URI(baseUrl).toURL().host.lowercase() in LOOPBACK

    /** Head key to the endpoint (`:8099` on this machine, `host:port` elsewhere) of each local head
     *  whose runtime does not answer; a head that is not a local runtime is never probed. When [includeRemote]
     *  is false, remote `local = true` runtimes outside loopback are skipped on this tick (#703). */
    fun notAnswering(
        topology: Topology,
        env: EnvReader = EnvReader(System::getenv),
        includeRemote: Boolean = true,
    ): Map<String, String> {
        val targets = topology.heads.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider]?.takeIf { it.isLocal } ?: return@mapNotNull null
            if (!includeRemote && !isLoopback(provider.baseUrl)) return@mapNotNull null
            val headers = probeInputs.headers(provider, probeInputs.bearer(key, provider, env))
            key to ProbeTarget(provider.baseUrl, headers)
        }
        if (targets.isEmpty()) return emptyMap()
        val silent = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val replies = targets.map { it.second }.distinct().associateWith { target ->
                CompletableFuture.supplyAsync({ answers(target) }, executor)
                    .completeOnTimeout(false, waitMs, TimeUnit.MILLISECONDS)
            }
            val answered = replies.mapValues { it.value.join() }
            executor.shutdownNow()
            answered.filterValues { !it }.keys
        }
        return targets.filter { it.second in silent }.associate { (key, target) -> key to endpoint(target.baseUrl) }
    }

    /** One request carrying the provider's headers and bearer (#703), so a keyed runtime answers 200 rather than
     *  logging 401s forever. Any HTTP reply counts as up; only silence is down. */
    private fun answers(target: ProbeTarget): Boolean {
        val client = http ?: healthHttp.with(target.headers)
        return client("GET", target.baseUrl.trimEnd('/') + "/models", null) != null
    }

    private fun endpoint(baseUrl: String): String {
        val url = URI(baseUrl).toURL()
        val port = url.port.takeIf { it > 0 } ?: url.defaultPort
        return if (url.host.lowercase() in LOOPBACK) ":$port" else "${url.host}:$port"
    }
}

private data class ProbeTarget(val baseUrl: String, val headers: Map<String, String>)

// Long enough for a runtime that is up but slow to reach the first byte, short enough that status
// stays a command you wait on; a refused connection answers at once, so only a wedge spends it.
private const val ANSWER_WAIT_MS = 3_000L
private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0")
