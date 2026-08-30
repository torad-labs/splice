package splice.gateway.head

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.SseSpuriousWakeupException
import splice.spi.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class AdmissionTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

private fun headDeps(tmp: Path, mirrorReasoning: Boolean = false) = HeadDeps(
    upstream = UpstreamClient(firstByteTimeoutMs = 1_000, totalTimeoutMs = 1_000, maxRetries = 1),
    inferenceToken = "test-inference-token",
    gate = InflightGate({ 1 }),
    shadow = ShadowClassifier(log = {}),
    compactStats = CompactStats(tmp.resolve("compact.jsonl")),
    usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
    perfStats = PerfStats(tmp.resolve("perf.jsonl")),
    log = {},
    mirrorReasoning = mirrorReasoning,
)

class AdmissionGateTest {
    @Test
    fun `head dependencies keep the reasoning mirror locked off`(@TempDir tmp: Path) {
        assertFalse(headDeps(tmp).mirrorReasoning)
        assertThrows(IllegalArgumentException::class.java) { headDeps(tmp, mirrorReasoning = true) }
    }

    @Test
    fun `a lying request channel is an invalid request rather than a server error`(
        @TempDir tmp: Path,
    ) = testApplication {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        )
        val provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = AdmissionTestAuth(),
                baseUrl = "http://127.0.0.1",
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        val deps = headDeps(tmp)
        val responses = AdmissionResponses()
        val admission = AdmissionGate(provider, deps, AdmissionWindow(), responses)
        val reader = RequestBodyReader(deps, RequestBodyRead { _, _ -> throw SseSpuriousWakeupException(1024) })

        application {
            routing {
                post("/probe") {
                    admission.materializeOrRespond(call) {
                        reader.receiveBodyBounded(call, deps.maxRequestBytes)
                    }
                }
            }
        }

        val response = client.post("/probe")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("invalid_request_error"), body)
        assertTrue(body.contains("request body stream interrupted"), body)
    }
}
