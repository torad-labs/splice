// NEW: V4-454 — loopback probes refuse before every work recorder, on both credential kinds.
package splice.head

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.TurnPrice
import splice.core.storage.ActivityDays
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.usage.EconomicsStore
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val PROBE_HEADER = "x-splice-liveness-probe"
private const val PROBE_BODY = """{"splice_liveness_probe":true}"""
private const val REAL_BODY = """{"model":"synthetic-model","stream":true,"max_tokens":1,"messages":[{"role":"user","content":"synthetic"}]}"""

class HeadServerLivenessProbeTest {
    @Test
    fun `forward-only probes never become work`(@TempDir tmp: Path) = exercise(true, tmp)

    @Test
    fun `API-key probes never become work`(@TempDir tmp: Path) = exercise(false, tmp)

    private fun exercise(forward: Boolean, tmp: Path) = runBlocking {
        val calls = AtomicInteger()
        val workEvents = AtomicInteger()
        val events = object : HeadEvents by NoHeadEvents {
            override fun turnStarted(session: String?) {
                workEvents.incrementAndGet()
            }
            override fun turnEnded(perfRowId: String, outcome: String, session: String?) {
                workEvents.incrementAndGet()
            }
        }
        val upstream = upstream(calls)
        try {
            isolatedHead(tmp, upstream.address.port, forward, events) { head, deps ->
                HttpClient(CIO).use { client ->
                    val credential = if (forward) "synthetic-client-key" else deps.tokens.inferenceToken
                    val cases = listOf(
                        Triple(PROBE_BODY, null, null),
                        Triple(PROBE_BODY, null, "1"),
                        Triple(REAL_BODY, credential, "1"),
                        Triple(REAL_BODY, credential, "0"),
                        Triple(REAL_BODY, credential, ""),
                    )
                    for (request in cases) {
                        reject(client, head.port, request)
                        noWork(deps, tmp.resolve("trace"), calls)
                        assertEquals(0, workEvents.get(), "no recent-turn event may name a probe")
                    }
                    // An unmarked control proves this listener really dispatches.
                    client.post("http://127.0.0.1:${head.port}/v1/messages") {
                        header("Content-Type", "application/json")
                        header("x-api-key", credential)
                        setBody(REAL_BODY)
                    }.bodyAsText()
                    assertTrue(calls.get() > 0)
                    withTimeout(5.seconds) {
                        while (deps.stores.perfStats.tailNumeric().isEmpty()) delay(10.milliseconds)
                    }
                    assertEquals(1, deps.stores.perfStats.tailNumeric().size)
                    assertEquals(2, workEvents.get())
                }
            }
        } finally {
            upstream.stop(0)
        }
    }

    private fun upstream(calls: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { exchange ->
                exchange.requestBody.readAllBytes()
                calls.incrementAndGet()
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
            }
            start()
        }

    private suspend fun reject(client: HttpClient, port: Int, request: Triple<String, String?, String?>) {
        val (body, key, marked) = request
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            if (key != null) header("x-api-key", key)
            if (marked != null) header(PROBE_HEADER, marked)
            setBody(body)
        }
        response.bodyAsText()
        assertTrue(response.status.value >= 400, "a probe cannot be served as work")
    }

    private fun noWork(deps: HeadDeps, traceDir: Path, calls: AtomicInteger) {
        assertEquals(0, calls.get(), "a marked real body must never ride upstream")
        assertEquals(emptyList<Map<String, Long>>(), deps.stores.perfStats.tailNumeric())
        assertEquals(0, deps.stores.usageStore.readState().entries)
        assertTrue(deps.stores.economicsStore!!.read().isEmpty())
        assertTrue(deps.traffic.liveTurns.list().isEmpty())
        AsyncFileIo.drain()
        assertTrue(!Files.exists(traceDir) || Files.list(traceDir).use { it.count() == 0L })
    }

    private suspend fun isolatedHead(
        tmp: Path,
        upstreamPort: Int,
        forward: Boolean,
        events: HeadEvents,
        exercise: suspend (HeadServer, HeadDeps) -> Unit,
    ) {
        val catalog = ModelCatalog(
            discoveryPrefix = "synthetic--",
            models = listOf(ModelEntry("synthetic-model", "Synthetic", contextWindow = 200_000)),
            defaultContextWindow = 200_000,
        )
        val auth = if (forward) ClientAuthProvider("synthetic") else ProbeApiKeyAuth()
        val provider = PassthroughProvider(
            ProviderTuning(
                name = ProviderName(key = "anthropic", label = "synthetic"),
                catalog = catalog,
                pinnedModel = "synthetic-model",
                auth = auth,
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:$upstreamPort"),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "synthetic"),
        )
        val economics = EconomicsStore(tmp.resolve("economics.json"), TurnPrice(catalog))
        val trace = splice.head.syntheticTraceStore(
            ActivityDays(tmp.resolve("trace"), "synthetic", 7),
            "synthetic",
            1000,
        )
        val deps = headDeps(
            tmp,
            seams = HeadDeps.HeadSeams(events = events),
        ).copy(
            policy = HeadDeps.HeadPolicy(forwardClientAuth = forward),
        ).copy(
            stores = headStores(tmp, economics = economics, trace = trace),
        )
        val head = HeadServer(provider, 0, deps)
        head.start()
        try {
            exercise(head, deps)
        } finally {
            head.stop()
        }
    }
}

private class ProbeApiKeyAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("synthetic-held-key", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
}
