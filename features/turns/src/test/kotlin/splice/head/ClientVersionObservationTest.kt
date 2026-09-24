package splice.head

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.GATEWAY_VERSION
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.ForeignHostLog
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.version.ClientVersionTracker
import splice.head.admission.AdmissionResponses
import splice.head.turn.TurnPreparation
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class ClientVersionObservationAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

class ClientVersionObservationTest {
    @Test
    fun `message request observes Claude CLI user agent for its client session`(
        @TempDir tmp: Path,
    ) = testApplication {
        val versions = ClientVersionTracker(testedVersion = "2.1.257")
        val provider = provider()
        val deps = dependencies(tmp, versions)
        val preparation = TurnPreparation(
            provider,
            deps,
            RequestBodyReader(deps.policy.requestReadTimeoutMs),
            AnthropicBodyParse(),
            ClientAuth(deps, AdmissionResponses(), ForeignHostLog("the test head", deps.log)),
        )
        application {
            routing {
                post("/v1/messages") {
                    preparation.prepareTurn(call, TurnPerf())
                    call.respondText("ok")
                }
            }
        }

        client.post("/v1/messages") {
            header(HttpHeaders.UserAgent, "claude-cli/2.1.258 (external, cli)")
            header("x-claude-code-session-id", "session-new")
            header(HttpHeaders.ContentType, "application/json")
            setBody(REQUEST)
        }

        assertEquals(
            "Claude Code 2.1.258 is newer than the version splice $GATEWAY_VERSION was tested with (2.1.257)",
            versions.aggregateWarning(),
        )
    }

    private fun provider() = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = ClientVersionObservationAuth(),
            baseUrl = "http://127.0.0.1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    private fun dependencies(tmp: Path, versions: ClientVersionTracker) = headDeps(
        tmp = tmp,
        upstream = UpstreamClient(totalTimeoutMs = 1_000, maxRetries = 1),
        gate = InflightGate({ 1 }),
        log = {},
        seams = HeadDeps.HeadSeams(clientVersions = versions),
    )
}

private const val REQUEST =
    """{"model":"claude-codex--gpt-5.6-sol","stream":false,"max_tokens":16,"messages":[{"role":"user","content":"hello"}]}"""
