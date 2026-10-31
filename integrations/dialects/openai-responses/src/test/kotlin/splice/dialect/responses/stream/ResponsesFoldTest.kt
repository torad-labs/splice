// Reasoning-continuation folding (codex 518n-2): the continuation-request builder. Pins the built
// continuation (prior input preserved + reasoning replayed via Replay.kt + the phase:commentary
// marker) and the round it cannot continue (no encrypted reasoning to replay). Failure-handling
// slice 4 moved the detector out: the 518n-2 fingerprint and its tier bound are CodexReplies'
// reading (CodexReasoningCutPropertyTest), and the continuation count is RetryEngine's budget.
package splice.dialect.responses.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.RoundHandoffs
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.FoldRound
import splice.core.reasoning.ReasoningReplay.decodeReasoningEnvelope as coreDecode

private val CONFIG = FoldConfig(models = setOf("gpt-5.6-luna"), markerText = "Continue thinking...")
private val controller = ResponsesFoldPolicy(CONFIG) { coreDecode(it) }

// A minimal but DTO-valid prior request (model/input/store/stream/instructions) — the continuation
// builder decodes this through the closed serializer, so it must round-trip.
private val priorRequest: JsonObject = Json.parseToJsonElement(
    """{"model":"gpt-5.6-luna","input":[{"role":"user","content":"solve it"}],
        "store":false,"stream":true,"instructions":"You are a test."}""",
).jsonObject

private fun envelopeFor(id: String): String = ReasoningReplay.encodeReasoningEnvelope(
    buildJsonObject {
        put("type", "reasoning")
        put("id", id)
        put("encrypted_content", "ENC-$id")
        put("summary", buildJsonArray {})
    },
)!!

private fun round(reasoningTokens: Long, envelopes: List<String>): FoldRound =
    FoldRound(
        requestBody = priorRequest,
        outcome = TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = Usage(inputTokens = 100, outputTokens = 600, cachedTokens = 0, reasoningTokens = reasoningTokens),
            handoffs = RoundHandoffs(reasoningEnvelopes = envelopes),
        ),
    )

class ResponsesFoldTest {

    @Test
    fun `a cut round with an envelope yields a continuation - prior input, reasoning replay, marker`() {
        val next = controller.continuation(round(516, listOf(envelopeFor("rs_1"))))
        assertNotNull(next)
        val input = next!!["input"]!!.jsonArray.map { it.jsonObject }
        // original user message preserved, in order
        assertEquals("solve it", input.first()["content"]?.jsonPrimitive?.content)
        // the round's reasoning replayed as a Responses reasoning input item (encrypted_content intact)
        val replayed = input.first { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals("ENC-rs_1", replayed["encrypted_content"]?.jsonPrimitive?.content)
        // a phase:commentary assistant marker as the LAST item (also the reasoning's following item)
        val marker = input.last()
        assertEquals("assistant", marker["role"]?.jsonPrimitive?.content)
        assertEquals("commentary", marker["phase"]?.jsonPrimitive?.content)
        assertEquals("Continue thinking...", marker["content"]?.jsonPrimitive?.content)
        // closed DTO stays intact: no stray request field, model/store/stream/instructions unchanged
        assertEquals("gpt-5.6-luna", next["model"]?.jsonPrimitive?.content)
        assertNull(next["stream_options"])
    }

    @Test
    fun `a cut round with no encrypted reasoning to replay - no continuation`() {
        assertNull(controller.continuation(round(516, emptyList())))
    }

    @Test
    fun `continuation keeps the summary request (turn-scoped dedup owns the repeats)`() {
        // Operator call 2026-07-26: "always show the reasoning, detailed" — the summary request
        // rides on continuations; exact repeats are suppressed in the stream translator instead.
        val withSummary = Json.parseToJsonElement(
            """{"model":"gpt-5.6-luna","input":[{"role":"user","content":"solve it"}],
                "store":false,"stream":true,
                "reasoning":{"effort":"high","summary":"detailed","context":"all_turns"},
                "stream_options":{"reasoning_summary_delivery":"sequential_cutoff"}}""",
        ).jsonObject
        val next = ResponsesContinuation().continuationRequest(withSummary, emptyList())
        val reasoning = next["reasoning"]!!.jsonObject
        assertEquals("detailed", reasoning["summary"]?.jsonPrimitive?.content)
        assertEquals("high", reasoning["effort"]?.jsonPrimitive?.content)
        assertEquals("all_turns", reasoning["context"]?.jsonPrimitive?.content)
        val delivery = next["stream_options"]!!.jsonObject["reasoning_summary_delivery"]?.jsonPrimitive?.content
        assertEquals("sequential_cutoff", delivery)
    }
}
