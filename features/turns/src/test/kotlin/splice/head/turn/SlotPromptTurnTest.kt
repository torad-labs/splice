// NEW: V4-131 — a bound session's slot text reaches the wire through the REAL TurnPreparation a head
// runs, on the passthrough dialect where every system layer is a visible block. It is APPENDED after
// the head's layers even when the head's own mode is replace, it is resolved per turn (an edit lands
// on the next turn with no restart), and the one turn whose slot text changed carries
// slot_prompt_changed=1 in its perf counters, which PerfStats writes into the turn's perf row.
// Claude Code's auto-mode review gets neither the head's layers nor the slot text: its policy goes upstream as written.
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
import org.junit.jupiter.api.Assertions.assertFalse
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
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SYSTEM_PROMPT_LAYERS
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
import splice.head.turn.review.AUTO_MODE_REVIEW
import splice.head.turn.review.AUTO_MODE_REVIEW_NEAR_MISS
import splice.head.turn.review.SIDE_REQUEST
import splice.sessions.prompt.SLOT_PROMPT_CHANGED
import splice.sessions.prompt.SlotInstructions
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import splice.sessions.teams.TeamStore
import splice.upstream.BuiltTurn
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
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

private const val REVIEW_POLICY = "You are a security monitor for autonomous AI coding agents. Judge the action."
private const val REWORDED_POLICY = "You are the security monitor for an autonomous coding agent."

/** A request whose system text is [system], as Claude Code's auto-mode review sends its policy. */
private fun reviewRequest(system: String): String =
    """{"model":"claude-kimi--$MODEL","stream":false,"max_tokens":64,""" +
        """"system":[{"type":"text","text":"$system"}],"messages":[{"role":"user","content":"Bash: rm -rf build"}]}"""

private const val CLAUDE_CODE = "claude-cli/2.1.326 (external, sdk-cli)"
private const val API_CALLER = "python-httpx/0.27.0"
private const val SUMMARY_INSTRUCTION = "Write a short summary label describing what these tool calls accomplished."

/** A request with no system text and no tools, as Claude Code sends a probe. */
private const val PROBE =
    """{"model":"claude-kimi--$MODEL","stream":false,"max_tokens":1,"messages":[{"role":"user","content":"quota"}]}"""

/** A session's turn: Claude Code's own prompt in the system field, and tools. */
private const val TURN_WITH_TOOLS =
    """{"model":"claude-kimi--$MODEL","stream":false,"max_tokens":16,""" +
        """"system":[{"type":"text","text":"house rules"}],"messages":[{"role":"user","content":"hello"}],""" +
        """"tools":[{"name":"Read","description":"Read a file.","input_schema":{"type":"object"}}]}"""

/** A request whose system text is [instruction] and that carries no tools, as Claude Code sends a side request. */
private fun sideRequest(instruction: String): String =
    """{"model":"claude-kimi--$MODEL","stream":false,"max_tokens":64,""" +
        """"system":[{"type":"text","text":"$instruction"}],"messages":[{"role":"user","content":"Tool: Bash"}]}"""

private class SlotTestAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("token", "account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

/** Every fixture body here is within the cap, so a refusal is the failure, named. */
private fun Materialized<Preparation>.prepared(): Preparation = when (this) {
    is Materialized.Done -> value
    is Materialized.TooLarge -> error("the fixture body cannot exceed the cap, refused at  bytes")
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
        assertEquals("N\n\n" + block("run the gate"), turn.meta.standingPrompt.text)
        assertEquals("head:kimi replace+slot:$id/b1", turn.meta.standingPrompt.source)
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
        assertEquals("head:kimi append", unbound.meta.standingPrompt.source)
        assertEquals(1L, cold[SLOT_PROMPT_CHANGED], "dropping the text changes the prefix too")
    }

    @Test
    fun `the auto-mode review reaches the upstream with its own policy and nothing of the head or the slot`() {
        bindBuilder("run the gate")
        val (turn, counters) = turn(SystemPromptMode.REPLACE, reviewRequest(REVIEW_POLICY))
        assertEquals(listOf(REVIEW_POLICY), systemTexts(turn), "neither the replacing layer nor the slot text")
        assertEquals(1L, counters[AUTO_MODE_REVIEW])
        assertNull(counters[SYSTEM_PROMPT_LAYERS], "no layer was applied, so none is counted")
    }

    @Test
    fun `a reworded review policy gets the head layers and is counted as a near miss`() {
        val (turn, counters) = turn(SystemPromptMode.APPEND, reviewRequest(REWORDED_POLICY))
        assertEquals(listOf(REWORDED_POLICY, "N"), systemTexts(turn))
        assertEquals(1L, counters[AUTO_MODE_REVIEW_NEAR_MISS])
        assertNull(counters[AUTO_MODE_REVIEW])
    }

    @Test
    fun `a Claude Code request without tools reaches the upstream with its own instruction and nothing of the head`() {
        bindBuilder("run the gate")
        val (turn, counters) = turn(SystemPromptMode.REPLACE, sideRequest(SUMMARY_INSTRUCTION), CLAUDE_CODE)
        assertEquals(listOf(SUMMARY_INSTRUCTION), systemTexts(turn), "neither the replacing layer nor the slot text")
        assertEquals(1L, counters[SIDE_REQUEST])
        assertNull(counters[SYSTEM_PROMPT_LAYERS], "no layer was applied, so none is counted")

        val (probe, _) = turn(SystemPromptMode.REPLACE, PROBE, CLAUDE_CODE)
        assertFalse("system" in probe.requestBody, "a request with no system text gains none")
        val (appended, _) = turn(SystemPromptMode.APPEND, sideRequest(SUMMARY_INSTRUCTION), CLAUDE_CODE)
        assertEquals(listOf(SUMMARY_INSTRUCTION), systemTexts(appended), "an appending layer stays out too")
    }

    @Test
    fun `a Claude Code turn that carries tools, and another client's request without them, get the head layers`() {
        val (turn, counters) = turn(SystemPromptMode.REPLACE, TURN_WITH_TOOLS, CLAUDE_CODE)
        assertEquals(listOf("N"), systemTexts(turn))
        assertNull(counters[SIDE_REQUEST])

        val (other, otherCounters) = turn(SystemPromptMode.REPLACE, sideRequest(SUMMARY_INSTRUCTION), API_CALLER)
        assertEquals(listOf("N"), systemTexts(other), "an operator's prompt rides on an API caller's request")
        assertNull(otherCounters[SIDE_REQUEST])
    }

    private fun systemTexts(turn: BuiltTurn): List<String> = turn.requestBody.getValue("system").jsonArray
        .map { it.jsonObject.getValue("text").jsonPrimitive.content }

    /** One request through a head wired with the shared [slots], and the turn's perf counters. */
    private fun turn(
        headMode: SystemPromptMode,
        body: String = REQUEST,
        userAgent: String? = null,
    ): Pair<BuiltTurn, Map<String, Long>> {
        val deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 1_000),
            gate = InflightGate({ 1 }),
            seams = HeadDeps.HeadSeams(session = HeadDeps.SessionSeams(slotInstructions = slots)),
        ).copy(
            policy = HeadDeps.HeadPolicy(
                systemPrompt = SystemPromptLayers(
                    HeadSystemPrompt(text = "N", mode = headMode, source = "head:kimi"),
                    headKey = "kimi",
                ),
            ),
        )
        val preparation = TurnPreparation(
            provider(),
            deps,
            RequestBodyReader(deps.policy.requestReadTimeoutMs),
            parser,
            ClientAuth(
                deps,
                AdmissionResponses(HeadVoice("scripted", loginCommand = null)),
                ForeignHostLog("the test head", deps.log),
            ),
        )
        val perf = TurnPerf()
        var captured: BuiltTurn? = null
        testApplication {
            application {
                routing {
                    post("/v1/messages") {
                        val result = preparation.prepareTurn(call, perf).prepared()
                        captured = (result as? Preparation.Ready)?.built ?: error("expected Ready, got $result")
                        call.respondText("ok")
                    }
                }
            }
            client.post("/v1/messages") {
                header(HttpHeaders.ContentType, "application/json")
                header("x-claude-code-session-id", SESSION)
                userAgent?.let { header(HttpHeaders.UserAgent, it) }
                setBody(body)
            }
        }
        return requireNotNull(captured) to perf.snapshot().counters
    }

    private fun provider() = PassthroughProvider(
        ProviderTuning(
            name = ProviderName(key = "kimi", label = "kimix"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-kimi--",
                models = listOf(ModelEntry(MODEL, "Kimi", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = MODEL,
            auth = SlotTestAuth(),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "test-passthrough"),
    )
}
