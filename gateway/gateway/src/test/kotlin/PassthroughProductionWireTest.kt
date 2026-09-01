// NEW: DR-142 — the passthrough translator driven into the REAL SseEmitter, asserting the BYTES a
// client receives. The passthrough module's own suite cannot express this defect: its Rec sink
// records `text:<value>` with no index, so a delta landing in the wrong block is indistinguishable
// from a correct one, and passthrough cannot depend on :gateway without a module cycle. That is the
// same fake-green shape codex caught on DR-119, which is why these arms live on this side of the
// dependency edge — :gateway already testImplementation's the dialect.
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughStreamTranslator
import splice.dialect.passthrough.PassthroughTurnContext
import splice.gateway.wire.SseEmitter
import splice.gateway.wire.SseEmitterFactory

class PassthroughProductionWireTest {

    private val emitters = SseEmitterFactory()

    private fun collector(): Pair<MutableList<String>, SseEmitter> {
        val frames = mutableListOf<String>()
        val emitter = emitters.create(
            write = { frames.add(it) },
            model = "claude-splice--claude-fable-5",
            usagePayload = { u ->
                buildJsonObject {
                    put("input_tokens", u?.inputTokens ?: 0)
                    put("output_tokens", u?.outputTokens ?: 0)
                }
            },
            messageId = "msg_142",
        )
        return frames to emitter
    }

    private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private suspend fun drive(sink: SseEmitter, vararg evs: JsonObject): TurnOutcome =
        PassthroughStreamTranslator(
            PassthroughTurnContext({ false }, { null }, IDLE_CAP_MS, TOTAL_CAP_MS),
            PassthroughQuirks(providerTag = "neutral"),
        ).driveTurn(evs.toList().asFlow(), sink)

    private fun deltasOf(frames: List<String>): List<String> =
        frames.filter { it.startsWith("event: content_block_delta") }

    // The isolated shape: one tool block, a text_delta aimed at it, and the tool's own delta. Before
    // the fix the text_delta was forwarded verbatim, so the client received a text_delta INSIDE a
    // tool_use block — protocol-corrupt — while PassthroughProseChannels latched emittedText, making
    // TurnOutcome claim text was delivered when the bytes went into a tool block.
    @Test
    fun `a text delta aimed at a tool block never reaches the production wire - DR-142`() = runTest {
        val (frames, e) = collector()
        val outcome = drive(
            e,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev(
                """{"type":"content_block_start","index":0,""" +
                    """"content_block":{"type":"tool_use","id":"t1","name":"Read"}}""",
            ),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"LEAK-142"}}"""),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        val wire = frames.joinToString("")
        assertFalse(wire.contains("LEAK-142"), "a mistargeted text_delta must never reach the wire: $wire")
        assertFalse(wire.contains("text_delta"), "no text_delta frame belongs in a tool-only turn: $wire")
        assertTrue(wire.contains("input_json_delta"), "the tool's OWN delta must still forward: $wire")

        val success = outcome as TurnOutcome.Success
        assertFalse(success.emittedText, "emittedText must not latch on a delta that was dropped")
        assertEquals("", success.bodyText, "bodyText must not collect a delta that never reached a text block")
    }

    // The mixed shape, which is the one Rec cannot express at all: a real text block AND a real tool
    // block open at different indices. Exactly one text_delta may reach the wire, and it must carry
    // the TEXT block's index — proving the drop is kind-aware rather than a blanket text_delta ban.
    @Test
    fun `a text delta lands only on the text block index - DR-142`() = runTest {
        val (frames, e) = collector()
        drive(
            e,
            ev("""{"type":"message_start","message":{"usage":{"input_tokens":1}}}"""),
            ev("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""),
            ev(
                """{"type":"content_block_start","index":1,""" +
                    """"content_block":{"type":"tool_use","id":"t1","name":"Read"}}""",
            ),
            ev("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"legit"}}"""),
            ev("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"LEAK-142"}}"""),
            ev("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}"""),
            ev("""{"type":"content_block_stop","index":0}"""),
            ev("""{"type":"content_block_stop","index":1}"""),
            ev("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""),
            ev("""{"type":"message_stop"}"""),
        )
        val textDeltas = deltasOf(frames).filter { it.contains("text_delta") }
        assertEquals(1, textDeltas.size, "exactly one text_delta belongs on this wire: $textDeltas")
        assertTrue(textDeltas[0].contains("\"index\":0"), "it must ride the TEXT block's index: ${textDeltas[0]}")
        assertTrue(textDeltas[0].contains("legit"), "and carry the legitimate text: ${textDeltas[0]}")

        val jsonDeltas = deltasOf(frames).filter { it.contains("input_json_delta") }
        assertEquals(1, jsonDeltas.size, "the tool's own delta must still forward: $jsonDeltas")
        assertTrue(jsonDeltas[0].contains("\"index\":1"), "and stay on the TOOL block's index: ${jsonDeltas[0]}")
    }
}

private const val IDLE_CAP_MS = 180_000L
private const val TOTAL_CAP_MS = 900_000L
