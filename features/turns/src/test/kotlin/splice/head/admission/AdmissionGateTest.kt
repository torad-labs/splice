package splice.head.admission

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import splice.head.HeadDeps
import splice.head.RequestBodyRead
import splice.head.RequestBodyReader
import splice.head.TestResponsesProvider
import splice.head.headStores
import splice.head.noQuota
import splice.head.turn.LiveTurns
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

// This file KEEPS its own local builder rather than importing the splice.head one: the two would
// share the name `headDeps` and the import would collide with this declaration. It composes the same
// bundles instead, which is all the fixture does anyway.
private fun headDeps(tmp: Path, mirrorReasoning: Boolean = false) = HeadDeps(
    upstream = UpstreamClient(totalTimeoutMs = 1_000, maxRetries = 1),
    inferenceToken = "test-inference-token",
    operatorToken = "test-operator-token",
    gate = InflightGate({ 1 }),
    liveTurns = LiveTurns(),
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
        val provider = provider()
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

    @Test
    fun `declared bodies reserve their size unknown bodies reserve the cap and oversized bodies keep 413`(
        @TempDir tmp: Path,
    ) = testApplication {
        val materialization = RequestMaterializationGate(heapBudgetBytes = 26)
        val deps = headDeps(tmp).copy(
            policy = HeadDeps.HeadPolicy(maxRequestBytes = 4),
            seams = HeadDeps.HeadSeams(requestMaterializationGate = materialization),
        )
        val admission = AdmissionGate(provider(), deps, AdmissionWindow(), AdmissionResponses())
        val lengths = mutableListOf<String?>()
        application {
            routing {
                post("/probe") {
                    lengths += call.request.headers[HttpHeaders.ContentLength]
                    val entered = admission.materializeOrRespond(call, fastFail = true) { "admitted" }
                    if (entered != null) call.respondText(entered)
                }
            }
        }

        coroutineScope {
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = async {
                materialization.withLease(1) {
                    acquired.complete(Unit)
                    release.await()
                }
            }
            acquired.await()
            try {
                val small = client.post("/probe") { setBody("hi") }
                assertEquals(HttpStatusCode.OK, small.status)
                val unknown = client.post("/probe") {
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel("hi")
                    })
                }
                assertEquals(529, unknown.status.value, unknown.bodyAsText())
                val oversized = client.post("/probe") { setBody("hello") }
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                assertEquals(listOf("2", null, "5"), lengths, "the fixture must really omit Content-Length")
            } finally {
                release.complete(Unit)
                holder.await()
            }
        }
    }

    private fun provider(): TestResponsesProvider = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
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
}
