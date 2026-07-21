// Reasoning-continuation folding (codex 518n-2): the truncation-fingerprint detector and the
// continuation-request builder. Pins the boundary math (516/1034/1552 truncate; 515/517/800/0 do
// not), the caps (tier window + continuation count + no-envelope), and the built continuation
// (prior input preserved + reasoning replayed via Replay.kt + the phase:commentary marker).
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.reasoning.encodeReasoningEnvelope
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.responses.FoldConfig
import splice.dialect.responses.ResponsesFoldController
import splice.spi.FoldRound
import splice.core.reasoning.decodeReasoningEnvelope as coreDecode

private val CONFIG = FoldConfig(models = setOf("gpt-5.6-luna"))
private val controller = ResponsesFoldController(CONFIG) { coreDecode(it) }

// A minimal but DTO-valid prior request (model/input/store/stream/instructions) — the continuation
// builder decodes this through the closed serializer, so it must round-trip.
private val priorRequest: JsonObject = Json.parseToJsonElement(
    """{"model":"gpt-5.6-luna","input":[{"role":"user","content":"solve it"}],
        "store":false,"stream":true,"instructions":"You are a test."}""",
).jsonObject

private fun envelopeFor(id: String): String = encodeReasoningEnvelope(
    buildJsonObject {
        put("type", "reasoning")
        put("id", id)
        put("encrypted_content", "ENC-$id")
        put("summary", buildJsonArray {})
    },
)!!

private fun round(reasoningTokens: Long, envelopes: List<String>, roundIndex: Int = 0): FoldRound =
    FoldRound(
        requestBody = priorRequest,
        outcome = TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = Usage(inputTokens = 100, outputTokens = 600, cachedTokens = 0, reasoningTokens = reasoningTokens),
            reasoningEnvelopes = envelopes,
        ),
        roundIndex = roundIndex,
    )

class ResponsesFoldTest {

    @Test
    fun `518n-2 fingerprint - 516 1034 1552 truncate, off-by-one and others do not`() {
        for (truncated in listOf(516L, 1034L, 1552L, 2070L)) {
            assertTrue(ResponsesFoldController.isTruncationFingerprint(truncated), "$truncated should be truncation")
        }
        for (clean in listOf(515L, 517L, 1035L, 2858L, 800L, 0L)) {
            assertFalse(ResponsesFoldController.isTruncationFingerprint(clean), "$clean should NOT be truncation")
        }
    }

    @Test
    fun `tier n derives from the fingerprint`() {
        assertEquals(1L, ResponsesFoldController.tierOf(516))
        assertEquals(2L, ResponsesFoldController.tierOf(1034))
        assertEquals(3L, ResponsesFoldController.tierOf(1552))
    }

    @Test
    fun `truncated round with an envelope yields a continuation - prior input, reasoning replay, marker`() {
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
    fun `not truncated - no continuation`() {
        assertNull(controller.continuation(round(800, listOf(envelopeFor("rs_1")))))
        assertNull(controller.continuation(round(0, listOf(envelopeFor("rs_1")))))
    }

    @Test
    fun `truncated but no encrypted reasoning to replay - no continuation`() {
        assertNull(controller.continuation(round(516, emptyList())))
    }

    @Test
    fun `continuation cap - stops once maxContinue rounds have folded`() {
        // roundIndex 0,1,2 continue (3 continuations), roundIndex 3 stops (== maxContinue default 3).
        assertNotNull(controller.continuation(round(516, listOf(envelopeFor("rs")), roundIndex = 2)))
        assertNull(controller.continuation(round(516, listOf(envelopeFor("rs")), roundIndex = 3)))
    }

    @Test
    fun `tier window - a tier above maxTier is released as-is`() {
        // tier 7 fingerprint = 518*7 - 2 = 3624, above the default maxTierN 6.
        assertNull(controller.continuation(round(3624, listOf(envelopeFor("rs")))))
    }
}
