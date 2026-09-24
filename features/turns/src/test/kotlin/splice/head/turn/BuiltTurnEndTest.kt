// NEW: V4-165 — what a provider holds for a turn (BuiltTurn.onEnd: a llama-server slot lease)
// ends exactly when the turn does. A turn handed to the drive keeps it until the admission slot is
// released; a build that throws between the provider and the drive gives it back at once, or the
// slot would read as busy forever and every later conversation would be sent unpinned.
package splice.head.turn

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.ForeignHostLog
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.TurnPerf
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptLayers
import splice.core.prompt.SystemPromptMode
import splice.core.turn.WatchdogBudget
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.AnthropicBodyParse
import splice.head.ClientAuth
import splice.head.HeadDeps
import splice.head.RequestBodyReader
import splice.head.admission.AdmissionResponses
import splice.head.headDeps
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.TurnEnd
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class EndTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

class BuiltTurnEndTest {

    /** A provider whose turns hold something, counting how often it is given back. */
    private class Holding(private val base: Provider, private val failPrompt: Boolean) : Provider by base {
        var ended = 0

        override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn =
            base.buildTurn(body, compact, sessionId).copy(onEnd = TurnEnd { ended += 1 })

        override fun withSystemPrompt(turn: BuiltTurn, prompt: String, mode: SystemPromptMode): BuiltTurn =
            if (failPrompt) error("system prompt layer failed") else base.withSystemPrompt(turn, prompt, mode)
    }

    @Test
    fun `a turn handed to the drive still holds what it built`(@TempDir tmp: Path) {
        val provider = Holding(base(), failPrompt = false)
        val result = prepare(tmp, provider)

        assertTrue(result is Preparation.Ready, "$result")
        assertEquals(0, provider.ended, "the drive's admission slot ends it, not the preparation")
    }

    // Mutant: drop endingOnFailure around the shaping step. The lease outlives a turn that never
    // ran, and its llama-server slot is never offered to another conversation again.
    @Test
    fun `a build that fails after the provider gives back what it held`(@TempDir tmp: Path) {
        val provider = Holding(base(), failPrompt = true)
        prepare(tmp, provider)

        assertEquals(1, provider.ended)
    }

    private fun prepare(tmp: Path, provider: Provider): Any? {
        val deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 1_000, maxRetries = 1),
            gate = InflightGate({ 1 }),
            policy = HeadDeps.HeadPolicy(
                systemPrompt = SystemPromptLayers(HeadSystemPrompt(text = "N", source = "head:kimi"), headKey = "kimi"),
            ),
        )
        val preparation = TurnPreparation(
            provider,
            deps,
            RequestBodyReader(deps.policy.requestReadTimeoutMs),
            AnthropicBodyParse(),
            ClientAuth(deps, AdmissionResponses(), ForeignHostLog("the test head", deps.log)),
        )
        var outcome: Any? = null
        testApplication {
            application {
                routing {
                    post("/v1/messages") {
                        outcome = runCatching { preparation.prepareTurn(call, TurnPerf()) }.fold({ it }, { it })
                        call.respondText("ok")
                    }
                }
            }
            client.post("/v1/messages") {
                header(HttpHeaders.ContentType, "application/json")
                header("x-claude-code-session-id", "session-v4165")
                setBody(REQUEST)
            }
        }
        return outcome
    }

    private fun base() = PassthroughProvider(
        ProviderTuning(
            key = "kimi",
            label = "kimix",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-kimi--",
                models = listOf(ModelEntry(MODEL, "Kimi", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = MODEL,
            auth = EndTestAuth(),
            baseUrl = "http://127.0.0.1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "test-passthrough"),
    )
}

private const val MODEL = "kimi-k2"

private const val REQUEST =
    """{"model":"claude-kimi--$MODEL","stream":false,"max_tokens":16,""" +
        """"system":[{"type":"text","text":"house rules"}],"messages":[{"role":"user","content":"hello"}]}"""
