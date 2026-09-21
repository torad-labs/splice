package splice.gateway.head

import campaign.v4105.headStores
import campaign.v4105.noQuota
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import mock.TestResponsesProvider
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
import splice.upstream.ProviderTuning
import splice.upstream.failure.SseSpuriousWakeupException
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class AdmissionTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

// This file KEEPS its own local builder rather than importing the campaign.v4105 one: the two would
// share the name `headDeps` and the import would collide with this declaration. It composes the same
// bundles instead, which is all the fixture does anyway.
private fun headDeps(tmp: Path, mirrorReasoning: Boolean = false) = HeadDeps(
    upstream = UpstreamClient(firstByteTimeoutMs = 1_000, totalTimeoutMs = 1_000, maxRetries = 1),
    inferenceToken = "test-inference-token",
    gate = InflightGate({ 1 }),
    log = {},
    stores = headStores(tmp),
    quotaBundle = noQuota(),
    policy = HeadDeps.HeadPolicy(mirrorReasoning = mirrorReasoning),
    seams = HeadDeps.HeadSeams(),
)

class AdmissionGateTest {
    @Test
    fun `head dependencies keep the reasoning mirror locked off`(@TempDir tmp: Path) {
        assertFalse(headDeps(tmp).policy.mirrorReasoning)
        assertThrows(IllegalArgumentException::class.java) { headDeps(tmp, mirrorReasoning = true) }
    }

    @Test
    fun `a lying request channel is a retryable timeout rather than a malformed request`(
        @TempDir tmp: Path,
    ) = testApplication {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        )
        val provider = TestResponsesProvider(
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
        val reader = RequestBodyReader(
            deps.policy.requestReadTimeoutMs,
            RequestBodyRead { _, _ -> throw SseSpuriousWakeupException(1024) },
        )

        application {
            routing {
                post("/probe") {
                    admission.materializeOrRespond(call) {
                        reader.receiveBodyBounded(call, deps.policy.maxRequestBytes)
                    }
                }
            }
        }

        val response = client.post("/probe")
        // 408, not 400 (DR-20): a torn CLIENT body is a retryable connection event, and 400 told
        // Claude Code its request itself was malformed — a non-retryable class for it.
        assertEquals(HttpStatusCode.RequestTimeout, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("invalid_request_error"), body)
        assertTrue(body.contains("request body stream interrupted"), body)
    }
}
