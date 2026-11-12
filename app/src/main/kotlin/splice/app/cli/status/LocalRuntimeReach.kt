// NEW: V4-415. Whether each local head's runtime answers right now, asked by `splice status`. A local
// head boots even when its runtime is down (ChatArm logs it and turns fail until it is up), so the
// wrapper and the key say nothing about whether a turn would work; the runtime's own answer does.
package splice.app.cli.status

import splice.app.provider.JdkLocalHttp
import splice.core.topology.Topology
import splice.upstream.transport.LocalHttp
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Probes every distinct local runtime at once and gives each at most [waitMs]: status is a
 *  command an operator waits on, so a wedged runtime reads as not answering instead of holding it. */
internal class LocalRuntimeReach(
    private val http: LocalHttp = JdkLocalHttp(),
    private val waitMs: Long = ANSWER_WAIT_MS,
) {
    /** Head key to the endpoint (`:8099` on this machine, `host:port` elsewhere) of each local head
     *  whose runtime does not answer; a head that is not a local runtime is never probed. */
    fun notAnswering(topology: Topology): Map<String, String> {
        val urls = topology.heads.mapNotNull { (key, head) ->
            topology.providers[head.provider]?.takeIf { it.isLocal }?.let { key to it.baseUrl }
        }
        if (urls.isEmpty()) return emptyMap()
        val silent = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val replies = urls.map { it.second }.distinct().associateWith { url ->
                CompletableFuture.supplyAsync({ answers(url) }, executor)
                    .completeOnTimeout(false, waitMs, TimeUnit.MILLISECONDS)
            }
            val answered = replies.mapValues { it.value.join() }
            executor.shutdownNow()
            answered.filterValues { !it }.keys
        }
        return urls.filter { it.second in silent }.associate { (key, url) -> key to endpoint(url) }
    }

    /** One request, so a wedged runtime costs one wait where LocalRuntimeProbe.detect costs three. Any
     *  reply counts, an error status included: a runtime that guards /v1/models (401) is up, and
     *  only silence is down. */
    private fun answers(baseUrl: String): Boolean = http("GET", baseUrl.trimEnd('/') + "/models", null) != null

    private fun endpoint(baseUrl: String): String {
        val url = URI(baseUrl).toURL()
        val port = url.port.takeIf { it > 0 } ?: url.defaultPort
        return if (url.host.lowercase() in LOOPBACK) ":$port" else "${url.host}:$port"
    }
}

// Long enough for a runtime that is up but slow to reach the first byte, short enough that status
// stays a command you wait on; a refused connection answers at once, so only a wedge spends it.
private const val ANSWER_WAIT_MS = 3_000L
private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0")
