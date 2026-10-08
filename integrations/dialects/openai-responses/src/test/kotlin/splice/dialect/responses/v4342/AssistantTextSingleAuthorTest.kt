// NEW: V4-342 — one author for the lite assistant text item. A tool-search round replays the model's
// prose into its continuation request, and that request can be the one whose answer starts a code-mode
// script: the prose is then part of the script's baseline. The client later replays the same prose
// through the builder, which writes it with the phase its message's shape gives it (commentary: a
// tool_use follows it). Live on 2026-09-26 record 923aa85d was abandoned on exactly that item, logical
// 4 of 5: {role,content} in the baseline, {role,phase:commentary,content} in the resumed body. The
// re-anchor and fold markers are the other hand-built assistant items (the rule's census); they go
// through the same factory with their bytes unchanged.
package splice.dialect.responses.v4342

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.ReasoningDisplay
import splice.core.turn.ToolSearchCall
import splice.core.turn.ToolSearchCallId
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.wire.ToolDefinition
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.ResponsesReanchorPolicy
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.stream.FoldConfig
import splice.dialect.responses.stream.ResponsesFoldPolicy
import splice.dialect.responses.tools.ResponsesToolSearchPolicy
import splice.dialect.responses.tools.ToolDeferralPolicy
import splice.dialect.responses.tools.ToolSearchIndex
import splice.upstream.FoldRound
import splice.upstream.ReanchorRound
import splice.upstream.ToolSearchRound

private const val PROSE = "Let me find the right tool."

private val LITE = ResponsesQuirks(providerTag = "claudex", responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6"))

/** The re-anchor marker as it rode the wire before the factory wrote it, byte for byte. */
private const val MARKER_BYTES = """{"role":"assistant","phase":"commentary","content":"Your previous stream was """ +
    """interrupted mid-answer. Continue EXACTLY where the text above stops. Do not repeat or restate anything """ +
    """already written, and do not restate reasoning you have already given."}"""

/** The fold's marker as it rode the wire before the factory wrote it, byte for byte. */
private const val FOLD_MARKER_BYTES = """{"role":"assistant","phase":"commentary","content":"Continue thinking..."}"""

class AssistantTextSingleAuthorTest {

    @Test
    fun `the prose a tool-search round replays is the item the builder writes for it - V4-342`() {
        val search = ToolSearchCall(
            callId = ToolSearchCallId("ts_1"),
            query = "tool_0",
            limit = null,
            raw = buildJsonObject {
                put("type", "tool_search_call")
                put("call_id", "ts_1")
                put("execution", "client")
                put("arguments", """{"query":"tool_0"}""")
            },
        )
        val round = TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = Usage(),
            bodyText = PROSE,
            emittedText = true,
            toolSearches = listOf(search),
        )
        val prior = prior("Go.")
        val continuation = searchController().continuationForSearch(ToolSearchRound(prior, round, 0))!!

        val replayed = continuation.getValue("input").jsonArray[1].jsonObject

        assertEquals(builderReplayOfProse().toString(), replayed.toString())
    }

    @Test
    fun `the re-anchor marker is the factory's commentary item, byte for byte - V4-342`() {
        val controller = ResponsesReanchorPolicy(decodeReasoningEnvelope = { null })
        val prior = prior("hi")
        val failure = TurnOutcome.Failure(
            "boom",
            cause = FailureCause.UPSTREAM_STALLED,
            phase = FailurePhase.MID_OUTPUT,
            partial = TurnOutcome.PartialRound(bodyText = "The fix is to"),
        )

        val input = controller.continuationForFailure(ReanchorRound(prior, failure, attempt = 0))!!
            .getValue("input").jsonArray

        assertEquals(MARKER_BYTES, input.last().toString())
    }

    @Test
    fun `the fold's continuation marker is the factory's commentary item, byte for byte - V4-342`() {
        val controller = ResponsesFoldPolicy(FoldConfig(models = setOf("gpt-5.6-luna"))) {
            Json.parseToJsonElement("""{"type":"reasoning","id":"$it"}""").jsonObject
        }
        val truncated = TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = Usage(reasoningTokens = 516),
            reasoningEnvelopes = listOf("rs_1"),
        )

        val input = controller.continuation(FoldRound(prior("solve it"), truncated, roundIndex = 0))!!
            .getValue("input").jsonArray

        assertEquals(FOLD_MARKER_BYTES, input.last().toString())
    }

    private fun prior(user: String): JsonObject = Json.parseToJsonElement(
        """{"model":"gpt-5.6-sol","input":[{"role":"user","content":"$user"}],"store":false,"stream":true}""",
    ).jsonObject

    private fun searchController() = ResponsesToolSearchPolicy(
        index = ToolSearchIndex(List(4) { ToolDefinition(name = "tool_$it", description = "tool $it") }),
        policy = ToolDeferralPolicy(searchLimit = 4, searchRounds = 3),
        emitStrict = false,
        forceStrictFalse = false,
        normalizeSchemas = false,
        decodeReasoningEnvelope = { null },
    )

    /** The client's replay of the same turn, [PROSE] and then the call the turn went on to, through the
     *  lite builder: the assistant item it writes for [PROSE]. */
    private fun builderReplayOfProse(): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"claude-codex--gpt-5.6-sol","max_tokens":100,"messages":[
                {"role":"user","content":"Go."},
                {"role":"assistant","content":[
                  {"type":"text","text":"$PROSE"},
                  {"type":"tool_use","id":"call_1","name":"Read","input":{}}]}]}""",
        )
        val opts = BuildOptions(
            compact = false,
            originalModel = "claude-codex--gpt-5.6-sol",
            upstreamModel = "gpt-5.6-sol",
            configEffort = "high",
            configSummary = null,
            showReasoning = ReasoningDisplay.OFF,
            replayReasoning = InjectPriorReasoning(false),
            decodeReasoningEnvelope = { null },
        )
        val input = ResponsesRequestBuilder(LITE).build(parsed.typed, parsed.raw, opts).req.getValue("input").jsonArray
        return input.map { it.jsonObject }.single { "role" in it && it["content"].toString().contains(PROSE) }
    }
}
