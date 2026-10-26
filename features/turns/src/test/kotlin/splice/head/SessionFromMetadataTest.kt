package splice.head

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.ForeignHostLog
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.HeadVoice
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.version.ClientVersionTracker
import splice.dialect.responses.ReasoningSettings
import splice.head.admission.AdmissionResponses
import splice.head.turn.Materialized
import splice.head.turn.Preparation
import splice.head.turn.TurnPreparation
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class SessionFromMetadataAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

class SessionFromMetadataTest {
    // A streaming turn with a session is the one a stop can mark (messagesHash), so a hash is the visible proof the
    // request was placed in a session: here the only place the session is named is the body's metadata.user_id.
    @Test
    fun `a request names its session in the body metadata, and the header stands in when the body does not`(
        @TempDir tmp: Path,
    ) = testApplication {
        val deps = dependencies(tmp, ClientVersionTracker(testedVersion = "2.1.257"))
        val preparation = TurnPreparation(
            provider(),
            deps,
            RequestBodyReader(deps.policy.requestReadTimeoutMs),
            AnthropicBodyParse(),
            ClientAuth(
                deps,
                AdmissionResponses(HeadVoice("scripted", loginCommand = null)),
                ForeignHostLog("the test head", deps.log),
            ),
        )
        val hashes = mutableMapOf<String, String?>()
        application {
            routing {
                post("/v1/messages") {
                    val done = preparation.prepareTurn(call, TurnPerf()) as Materialized.Done<Preparation>
                    val ready = done.value as Preparation.Ready
                    hashes[call.request.queryParameters["case"].orEmpty()] = ready.messagesHash
                    call.respondText("ok")
                }
            }
        }

        client.post("/v1/messages?case=body") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(streaming("""{"device_id":"d","account_uuid":"a","session_id":"s-body"}"""))
        }
        client.post("/v1/messages?case=header") {
            header("x-claude-code-session-id", "s-header")
            header(HttpHeaders.ContentType, "application/json")
            setBody(streaming(null))
        }
        client.post("/v1/messages?case=neither") {
            header(HttpHeaders.ContentType, "application/json")
            setBody(streaming("not json"))
        }

        assertNotNull(hashes["body"], "the body's metadata names the session")
        assertNotNull(hashes["header"], "no metadata: the header names it")
        assertNull(hashes["neither"], "an unreadable user_id and no header: no session")
    }

    private fun streaming(userId: String?): String {
        val metadata = userId?.let { ""","metadata":{"user_id":${Json.encodeToString(it)}}""" }.orEmpty()
        return """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":16,""" +
            """"messages":[{"role":"user","content":"hello"}]$metadata}"""
    }

    private fun provider() = TestResponsesProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = "codex", label = "claudex"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = SessionFromMetadataAuth(),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    private fun dependencies(tmp: Path, versions: ClientVersionTracker) = headDeps(
        tmp = tmp,
        upstream = UpstreamClient(totalTimeoutMs = 1_000, maxRetries = 1),
        gate = InflightGate({ 1 }),
        log = {},
        seams = HeadDeps.HeadSeams(clientVersions = versions),
    )
}
