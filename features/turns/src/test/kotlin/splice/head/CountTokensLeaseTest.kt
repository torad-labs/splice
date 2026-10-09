package splice.head

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.memory.HeapBudget
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.admission.AdmissionGate
import splice.head.admission.AdmissionResponses
import splice.head.admission.AdmissionWindow
import splice.head.admission.RequestMaterializationGate
import splice.upstream.ProviderTuning
import splice.upstream.memory.JvmHeap
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val BODY_BYTES = 512
private const val INPUT = "x-synthetic-input"
private const val PROBE = "x-synthetic-probe"

class CountTokensLeaseTest {
    @Test
    fun `back to back near budget estimates and rejections release before publication`(
        @TempDir tmp: Path,
    ) = testApplication {
        val bodies = listOf(
            "{".padEnd(BODY_BYTES) to 400,
            """{"model":"synthetic-model","max_tokens":1,"messages":[{"role":"user","content":"synthetic"}]}"""
                .padEnd(BODY_BYTES) to 200,
        )
        val deps = headDeps(
            tmp,
            seams = HeadDeps.HeadSeams(
                requestMaterializationGate = RequestMaterializationGate(
                    heap = HeapBudget(JvmHeap.limitBytes, BODY_BYTES * 13L / 2L),
                ),
            ),
        ).copy(
            policy = HeadDeps.HeadPolicy(maxRequestBytes = BODY_BYTES),
        )
        val handler = handler(deps)
        val publicationProbes = mutableListOf<Int>()
        val probeClient = client
        application {
            sendPipeline.intercept(ApplicationSendPipeline.Before) {
                if (context.request.headers[PROBE] == null) {
                    val input = checkNotNull(context.request.headers[INPUT]).toInt()
                    publicationProbes += probeClient.post("/count") {
                        header(HttpHeaders.Authorization, "Bearer test-inference-token")
                        header(PROBE, "true")
                        setBody(bodies[input].first)
                    }.status.value
                }
            }
            routing { post("/count") { handler.handleCountTokens(call) } }
        }
        repeat(3) {
            for ((input, body) in bodies.withIndex()) {
                val response = client.post("/count") {
                    header(HttpHeaders.Authorization, "Bearer test-inference-token")
                    header(INPUT, input.toString())
                    setBody(body.first)
                }
                assertEquals(body.second, response.status.value, response.bodyAsText())
            }
        }
        assertEquals(List(3) { bodies.map { it.second } }.flatten(), publicationProbes, "no immediate retry gets 529")
        assertEquals(0L, deps.gate.snapshot().acquired, "count_tokens never takes a turn permit")
    }

    private fun handler(deps: HeadDeps): CountTokens {
        val provider = TestResponsesProvider(
            ProviderTuning(
                key = "synthetic",
                label = "Synthetic",
                catalog = ModelCatalog(
                    discoveryPrefix = "synthetic--",
                    models = listOf(ModelEntry("synthetic-model", "Synthetic", contextWindow = 1000)),
                    defaultContextWindow = 1000,
                ),
                pinnedModel = "synthetic-model",
                auth = object : RefreshableAuthProvider {
                    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token")
                    override suspend fun refresh(): Credentials = credentials()
                    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
                },
                baseUrl = "http://127.0.0.1:9",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 20.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.OFF, false, null, null),
        )
        val responses = AdmissionResponses()
        return CountTokens(
            provider,
            deps,
            AdmissionGate(provider, deps, AdmissionWindow(), responses),
            RequestBodyReader(1000),
            AnthropicBodyParse(),
            responses,
        )
    }
}
