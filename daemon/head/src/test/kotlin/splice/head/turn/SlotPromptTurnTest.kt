// NEW: V4-131 — a bound session's slot text reaches the wire through the REAL TurnPreparation a head
// runs, on the passthrough dialect where every system layer is a visible block. It is APPENDED after
// the head's layers even when the head's own mode is replace, it is resolved per turn (an edit lands
// on the next turn with no restart), and the one turn whose slot text changed carries
// slot_prompt_changed=1 in its perf counters, which PerfStats writes into the turn's perf row.
package splice.head.turn

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SLOT_PROMPT_CHANGED
import splice.core.prompt.SlotInstructions
import splice.core.prompt.SystemPromptLayers
import splice.core.prompt.SystemPromptMode
import splice.core.teams.Team
import splice.core.teams.TeamSlot
import splice.core.teams.TeamStore
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
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val SESSION = "session-v4131"
private const val MODEL = "kimi-k2"
private const val REQUEST =
    """{"model":"claude-kimi--$MODEL","stream":false,"max_tokens":16,""" +
        """"system":[{"type":"text","text":"house rules"}],"messages":[{"role":"user","content":"hello"}]}"""

private class SlotTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

class SlotPromptTurnTest {

    @TempDir
    lateinit var tmp: Path

    private val store by lazy { TeamStore(tmp.resolve("teams.json")) }
    private val slots by lazy { SlotInstructions(store) }
    private val parser = AnthropicBodyParse()

    private fun bindBuilder(instructions: String): String {
        val id = store.upsert(
            Team(
                name = "atlas",
                slots = listOf(TeamSlot(id = "b1", role = "builder", head = "kimi", instructions = instructions)),
            ),
        ).id
        store.bind(id, mapOf("b1" to SESSION))
        return id
    }

    private fun block(instructions: String) =
        "[splice team \"atlas\", slot b1]\nYour role: builder. The team has no lead slot.\n\n$instructions"

    @Test
    fun `the slot text is appended after a replacing head layer, and its source is named`() {
        val id = bindBuilder("run the gate")
        val (turn, counters) = turn(SystemPromptMode.REPLACE)
        assertEquals(listOf("N", block("run the gate")), systemTexts(turn), "appended even on a replace head")
        assertEquals("N\n\n" + block("run the gate"), turn.meta.systemPrompt)
        assertEquals("head:kimi replace+slot:$id/b1", turn.meta.systemPromptSource)
        assertNull(counters[SLOT_PROMPT_CHANGED], "the first turn seen is not a change")
    }

    @Test
    fun `an edit lands on the next turn, and only the turn that changed is marked cold`() {
        val id = bindBuilder("run the gate")
        turn(SystemPromptMode.APPEND)
        store.instruct(id, "b1", "stop and report")
        val (edited, changed) = turn(SystemPromptMode.APPEND)
        assertEquals(listOf("house rules", "N", block("stop and report")), systemTexts(edited))
        assertEquals(1L, changed[SLOT_PROMPT_CHANGED])
        assertNull(turn(SystemPromptMode.APPEND).second[SLOT_PROMPT_CHANGED], "an unchanged turn is not marked")
        store.bind(id, mapOf("b1" to null))
        val (unbound, cold) = turn(SystemPromptMode.APPEND)
        assertEquals(listOf("house rules", "N"), systemTexts(unbound), "unbound: the head's layers only")
        assertEquals("head:kimi append", unbound.meta.systemPromptSource)
        assertEquals(1L, cold[SLOT_PROMPT_CHANGED], "dropping the text changes the prefix too")
    }

    private fun systemTexts(turn: BuiltTurn): List<String> = turn.requestBody.getValue("system").jsonArray
        .map { it.jsonObject.getValue("text").jsonPrimitive.content }

    /** One request through a head wired with the shared [slots], and the turn's perf counters. */
    private fun turn(headMode: SystemPromptMode): Pair<BuiltTurn, Map<String, Long>> {
        val deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(firstByteTimeoutMs = 1_000, totalTimeoutMs = 1_000, maxRetries = 1),
            gate = InflightGate({ 1 }),
            policy = HeadDeps.HeadPolicy(
                systemPrompt = SystemPromptLayers(
                    HeadSystemPrompt(text = "N", mode = headMode, source = "head:kimi"),
                    headKey = "kimi",
                ),
            ),
            seams = HeadDeps.HeadSeams(slotInstructions = slots),
        )
        val preparation = TurnPreparation(
            provider(),
            deps,
            RequestBodyReader(deps.policy.requestReadTimeoutMs),
            parser,
            ClientAuth(deps, AdmissionResponses()),
        )
        val perf = TurnPerf()
        var captured: BuiltTurn? = null
        testApplication {
            application {
                routing {
                    post("/v1/messages") {
                        val result = preparation.prepareTurn(call, perf)
                        captured = (result as? Preparation.Ready)?.built ?: error("expected Ready, got $result")
                        call.respondText("ok")
                    }
                }
            }
            client.post("/v1/messages") {
                header(HttpHeaders.ContentType, "application/json")
                header("x-claude-code-session-id", SESSION)
                setBody(REQUEST)
            }
        }
        return requireNotNull(captured) to perf.snapshot().counters
    }

    private fun provider() = PassthroughProvider(
        ProviderTuning(
            key = "kimi",
            label = "kimix",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-kimi--",
                models = listOf(ModelEntry(MODEL, "Kimi", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = MODEL,
            auth = SlotTestAuth(),
            baseUrl = "http://127.0.0.1",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "test-passthrough"),
    )
}
