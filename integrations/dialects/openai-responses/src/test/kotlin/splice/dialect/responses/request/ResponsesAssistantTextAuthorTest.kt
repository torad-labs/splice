// One author for the lite assistant text item. A tool-search round replays the model's prose into its
// continuation request, and that request can be the one whose answer starts a code-mode script: the
// prose is then part of the script's baseline. The client later replays the same prose through the
// builder, which writes it with the phase its message's shape gives it (commentary: a tool_use follows
// it). If the two disagree on a single byte the prompt cache forks at that item.
package splice.dialect.responses.request

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.ToolSearchCall
import splice.core.turn.ToolSearchCallId
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.wire.ToolDefinition
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.tools.ResponsesToolSearchPolicy
import splice.dialect.responses.tools.ToolDeferralPolicy
import splice.dialect.responses.tools.ToolSearchIndex
import splice.upstream.ToolSearchRound

private const val PROSE = "Let me find the right tool."

private val LITE = ResponsesQuirks(
    providerTag = "claudex",
    lite = ResponsesLiteQuirks(
        responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6"),
    ),
)

class ResponsesAssistantTextAuthorTest {

    @Test
    fun `the prose a tool-search round replays is the item the builder writes for it`() {
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
            text = RoundText(bodyText = PROSE, emittedText = true),
            handoffs = RoundHandoffs(toolSearches = listOf(search)),
        )
        val prior = prior("Go.")
        val continuation = searchController().continuationForSearch(ToolSearchRound(prior, round, 0))!!

        val replayed = continuation.getValue("input").jsonArray[1].jsonObject

        assertEquals(builderReplayOfProse().toString(), replayed.toString())
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
            models = ModelIds(
                original = "claude-codex--gpt-5.6-sol",
                upstream = "gpt-5.6-sol",
            ),
            reasoning = RequestedReasoning(
                effort = "high",
                summary = null,
                display = ReasoningDisplay.OFF,
            ),
            handoff = ReasoningHandoff(
                replay = InjectPriorReasoning(false),
                decode = { null },
            ),
        )
        val input = ResponsesRequestBuilder(LITE).build(parsed.typed, parsed.raw, opts).req.getValue("input").jsonArray
        return input.map { it.jsonObject }.single { "role" in it && it["content"].toString().contains(PROSE) }
    }
}
