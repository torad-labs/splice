// V4-36: the head's standing system prompt, exercised through the REAL turn path — the same
// TurnPreparation a head runs, over a real HTTP call, for the two dialects :gateway:test carries.
// Pins the campaign WALLS invariant (nothing configured = today's bytes, exactly) plus the
// acceptance the row names: position/bytes stable across turns, no cross-head leak, and the
// honesty rule for a dialect that cannot place the prompt.
package head

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mock.TestResponsesProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicTurnBody
import splice.core.perf.TurnPerf
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptMode
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.passthrough.PassthroughProvider
import splice.dialect.passthrough.PassthroughQuirks
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.AdmissionResponses
import splice.gateway.head.AnthropicBodyParse
import splice.gateway.head.ClientAuth
import splice.gateway.head.HeadDeps
import splice.gateway.head.Preparation
import splice.gateway.head.RequestBodyReader
import splice.gateway.head.TurnPreparation
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.spi.BuiltTurn
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class PromptTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

/** A provider whose dialect has NOT opted into the capability: the SPI default hands the turn
 *  straight back, which is exactly what the honesty rule has to report. */
private class UnplaceableProvider(delegate: Provider) : Provider by delegate {
    override fun withSystemPrompt(turn: BuiltTurn, prompt: String, mode: SystemPromptMode): BuiltTurn = turn
}

class TurnPreparationSystemPromptTest {

    private val parser = AnthropicBodyParse()

    @Test
    fun `a head that configures no prompt sends exactly the bytes its provider built`(
        @TempDir tmp: Path,
    ) {
        val prepared = preparedTurn(preparation(tmp, responsesProvider(), HeadSystemPrompt()), RESPONSES_REQUEST)
        val direct = responsesProvider().buildTurn(parsed(RESPONSES_REQUEST), compact = false, sessionId = null)

        assertEquals(direct.requestBody.toString(), prepared.requestBody.toString())
        assertNull(prepared.meta.systemPrompt)
        assertNull(prepared.meta.systemPromptSource)

        val passthrough = preparedTurn(
            preparation(tmp, passthroughProvider(), HeadSystemPrompt()),
            PASSTHROUGH_REQUEST,
        )
        val passthroughDirect =
            passthroughProvider().buildTurn(parsed(PASSTHROUGH_REQUEST), compact = false, sessionId = null)

        assertEquals(passthroughDirect.requestBody.toString(), passthrough.requestBody.toString())
        assertNull(passthrough.meta.systemPrompt)
    }

    @Test
    fun `an append prompt rides once on every turn and the meta names it`(
        @TempDir tmp: Path,
    ) {
        val prepared = preparedTurn(
            preparation(tmp, responsesProvider(), HeadSystemPrompt(text = "Be terse.", source = "head:codex")),
            RESPONSES_REQUEST,
        )
        val entries = prepared.requestBody.getValue("input").jsonArray

        assertEquals(1, entries.count { it.toString().contains("Be terse.") })
        assertEquals("developer", entries.last().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("Be terse.", prepared.meta.systemPrompt)
        assertEquals("head:codex append", prepared.meta.systemPromptSource)
        assertEquals("house rules", prepared.requestBody.getValue("instructions").jsonPrimitive.content)
    }

    @Test
    fun `a replace prompt becomes the client's whole system field on the passthrough wire`(
        @TempDir tmp: Path,
    ) {
        val prepared = preparedTurn(
            preparation(
                tmp,
                passthroughProvider(),
                HeadSystemPrompt(
                    text = "You are a bare model.",
                    mode = SystemPromptMode.REPLACE,
                    source = "head:kimi",
                ),
            ),
            PASSTHROUGH_REQUEST,
        )
        val blocks = prepared.requestBody.getValue("system").jsonArray

        assertEquals(1, blocks.size)
        assertEquals("You are a bare model.", blocks.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse(prepared.requestBody.toString().contains("house rules"))
        assertEquals("You are a bare model.", prepared.meta.systemPrompt)
        assertEquals("head:kimi replace", prepared.meta.systemPromptSource)
    }

    @Test
    fun `the prompt sits in the same position with the same bytes on a later turn`(
        @TempDir tmp: Path,
    ) {
        val prompt = HeadSystemPrompt(text = "Be terse.", source = "head:codex")
        val first = preparedTurn(preparation(tmp, responsesProvider(), prompt), RESPONSES_REQUEST)
            .requestBody.getValue("input").jsonArray
        val second = preparedTurn(preparation(tmp, responsesProvider(), prompt), RESPONSES_FOLLOW_UP)
            .requestBody.getValue("input").jsonArray

        assertEquals(first.last().toString(), second.last().toString())
        assertEquals(1, second.count { it.toString().contains("Be terse.") })
        assertEquals(
            first.dropLast(1).map { it.toString() },
            second.dropLast(1).take(first.size - 1).map { it.toString() },
        )
    }

    @Test
    fun `two heads with different prompts never leak into each other`(
        @TempDir tmp: Path,
    ) {
        val alpha = preparation(tmp, responsesProvider(), HeadSystemPrompt(text = "ALPHA ONLY"))
        val beta = preparation(
            tmp,
            responsesProvider(),
            HeadSystemPrompt(text = "BETA ONLY", mode = SystemPromptMode.REPLACE),
        )
        var fromAlpha: BuiltTurn? = null
        var fromBeta: BuiltTurn? = null

        testApplication {
            application {
                routing {
                    post("/alpha") {
                        fromAlpha = build(alpha, call)
                        call.respondText("ok")
                    }
                    post("/beta") {
                        fromBeta = build(beta, call)
                        call.respondText("ok")
                    }
                }
            }
            repeat(4) {
                coroutineScope {
                    val one = async { postJson("/alpha", RESPONSES_REQUEST) }
                    val two = async { postJson("/beta", RESPONSES_REQUEST) }
                    one.await()
                    two.await()
                }
            }
        }

        val alphaBody = requireNotNull(fromAlpha).requestBody.toString()
        val betaBody = requireNotNull(fromBeta).requestBody.toString()
        assertTrue(alphaBody.contains("ALPHA ONLY"), alphaBody)
        assertFalse(alphaBody.contains("BETA ONLY"), alphaBody)
        assertTrue(betaBody.contains("BETA ONLY"), betaBody)
        assertFalse(betaBody.contains("ALPHA ONLY"), betaBody)
    }

    @Test
    fun `a dialect that cannot place the prompt reports it as not applied`(
        @TempDir tmp: Path,
    ) {
        val provider = UnplaceableProvider(responsesProvider())
        val prepared = preparedTurn(
            preparation(tmp, provider, HeadSystemPrompt(text = "Be terse.", source = "head:codex")),
            RESPONSES_REQUEST,
        )

        assertNull(prepared.meta.systemPrompt)
        assertEquals("head:codex append (not applied)", prepared.meta.systemPromptSource)
        assertFalse(prepared.requestBody.toString().contains("Be terse."))
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────

    /** Runs one turn through a real application route and returns what the provider built. */
    private fun preparedTurn(preparation: TurnPreparation, request: String): BuiltTurn {
        var captured: BuiltTurn? = null
        testApplication {
            application {
                routing {
                    post("/v1/messages") {
                        captured = build(preparation, call)
                        call.respondText("ok")
                    }
                }
            }
            postJson("/v1/messages", request)
        }
        return requireNotNull(captured)
    }

    private suspend fun build(preparation: TurnPreparation, call: ApplicationCall): BuiltTurn {
        val result = preparation.prepareTurn(call, TurnPerf())
        return (result as? Preparation.Ready)?.built ?: error("expected a Ready preparation, got $result")
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postJson(path: String, body: String) {
        client.post(path) {
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
    }

    private fun preparation(tmp: Path, provider: Provider, prompt: HeadSystemPrompt): TurnPreparation {
        val deps = HeadDeps(
            upstream = UpstreamClient(firstByteTimeoutMs = 1_000, totalTimeoutMs = 1_000, maxRetries = 1),
            inferenceToken = "test-inference-token",
            gate = InflightGate({ 1 }),
            shadow = ShadowClassifier(log = {}),
            compactStats = CompactStats(tmp.resolve("compact.jsonl")),
            usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
            perfStats = PerfStats(tmp.resolve("perf.jsonl")),
            systemPrompt = prompt,
            log = {},
        )
        return TurnPreparation(
            provider,
            deps,
            RequestBodyReader(deps),
            parser,
            ClientAuth(deps, AdmissionResponses()),
        )
    }

    private fun parsed(request: String): AnthropicTurnBody = parser.parse(request).getOrThrow()

    private fun responsesProvider() = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry(RESPONSES_MODEL, "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = RESPONSES_MODEL,
            auth = PromptTestAuth(),
            baseUrl = "http://127.0.0.1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    private fun passthroughProvider() = PassthroughProvider(
        ProviderTuning(
            key = "kimi",
            label = "kimix",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-kimi--",
                models = listOf(ModelEntry(PASSTHROUGH_MODEL, "Kimi", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = PASSTHROUGH_MODEL,
            auth = PromptTestAuth(),
            baseUrl = "http://127.0.0.1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "test-passthrough"),
    )
}

private const val RESPONSES_MODEL = "gpt-5.6-sol"
private const val PASSTHROUGH_MODEL = "kimi-k2"

private const val RESPONSES_REQUEST =
    """{"model":"claude-codex--$RESPONSES_MODEL","stream":false,"max_tokens":16,"system":"house rules",""" +
        """"messages":[{"role":"user","content":"hello"}]}"""

private const val RESPONSES_FOLLOW_UP =
    """{"model":"claude-codex--$RESPONSES_MODEL","stream":false,"max_tokens":16,"system":"house rules",""" +
        """"messages":[{"role":"user","content":"hello"},""" +
        """{"role":"assistant","content":"hi"},{"role":"user","content":"again"}]}"""

private const val PASSTHROUGH_REQUEST =
    """{"model":"claude-kimi--$PASSTHROUGH_MODEL","stream":false,"max_tokens":16,""" +
        """"system":[{"type":"text","text":"house rules"}],"messages":[{"role":"user","content":"hello"}]}"""
