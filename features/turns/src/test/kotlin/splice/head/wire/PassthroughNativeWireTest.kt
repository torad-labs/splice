// NEW: native frame scopes preserve extensions without granting terminal authority.
package splice.head.wire

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.anthropic.PassthroughStreamTranslator
import splice.dialect.anthropic.PassthroughTurnContext
import splice.upstream.sse.WireSink

class PassthroughNativeWireTest {
    private fun event(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun translator(quirks: PassthroughQuirks = PassthroughQuirks(providerTag = "native")) =
        PassthroughStreamTranslator(PassthroughTurnContext({ false }, { null }, 180_000, 900_000), quirks)

    private fun emitter(frames: MutableList<String>): SseEmitter = SseEmitterFactory().create(
        write = { frames.add(it) },
        model = "native",
        usagePayload = { buildJsonObject { put("output_tokens", it?.outputTokens ?: 0) } },
        messageId = "fixed",
    )

    private fun decoded(frames: List<String>): List<JsonObject> = frames.flatMap { frame ->
        frame.lineSequence().filter { it.startsWith("data: ") }
            .map { event(it.removePrefix("data: ")) }.toList()
    }

    private fun kind(frame: JsonObject): String = frame["type"]?.jsonPrimitive?.content.orEmpty()

    private suspend fun drive(sink: WireSink, vararg frames: String): TurnOutcome {
        val translator = translator()
        translator.prepareSink(sink)
        return translator.driveTurn(frames.map(::event).asFlow(), sink)
    }

    @Test
    fun `notice insertion remaps future indices including closed blocks but not nested opaque indices`() = runTest {
        val writes = mutableListOf<String>()
        val sink = emitter(writes)
        val translator = translator()
        translator.prepareSink(sink)
        sink.ensureStarted()
        assertTrue(writes.isEmpty(), "preparation must run before a generated opener commits")
        val events = flow {
            emit(event("""{"type":"message_start","message":{"future":[null,false,3]}}"""))
            sink.progress { "synthetic wait" }
            emit(event("""{"type":"content_block_start","index":8,"content_block":{"type":"text","text":""}}"""))
            emit(event("""{"type":"content_block_delta","index":8,"delta":{"type":"text_delta","text":"hello"}}"""))
            emit(event("""{"type":"future_event","index":8,"opaque":{"index":77},"data":[null,false]}"""))
            emit(event("""{"type":"future_event","index":99,"never_opened":true}"""))
            emit(event("""{"type":"content_block_stop","index":8,"future_stop":false}"""))
            emit(event("""{"type":"future_after_stop","index":8,"opaque":{"index":77}}"""))
            emit(event("""{"type":"message_stop"}"""))
        }
        val outcome = translator.driveTurn(events, sink)
        assertTrue(outcome is TurnOutcome.Success)
        val frames = decoded(writes)
        val future = frames.first { kind(it) == "future_event" }
        assertEquals(event("""{"type":"future_event","index":1,"opaque":{"index":77},"data":[null,false]}"""), future)
        assertFalse(frames.any { it.containsKey("never_opened") })
        val stopped = frames.first { kind(it) == "content_block_stop" && it.containsKey("future_stop") }
        assertEquals(Json.parseToJsonElement("1"), stopped["index"])
        assertEquals(Json.parseToJsonElement("1"), frames.first { kind(it) == "future_after_stop" }["index"])
        assertTrue(frames.indexOf(stopped) < frames.indexOfFirst { kind(it) == "future_after_stop" })
        assertFalse(frames.filter { it["index"] == Json.parseToJsonElement("0") }.any { it.containsKey("future_stop") })
    }

    @Test
    fun `raw deltas and all opaque extension shapes survive collected finalization`() = runTest {
        val sink = CollectingTerminal("native", { buildJsonObject { put("output_tokens", 0) } })
        val outcome = drive(
            sink,
            """{"type":"message_start","future_start":null,"message":{"future_message":[null,false]}}""",
            """{"type":"content_block_start","index":5,"block_outer":false,"content_block":{"type":"future","payload":{"old":1},"null_field":null}}""",
            """{"type":"content_block_delta","index":5,"delta_outer":[false],"delta":{"type":"future_delta","payload":{"new":2}}}""",
            """{"type":"content_block_stop","index":5,"stop_outer":null}""",
            """{"type":"content_block_start","index":6,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":6,"delta":{"type":"text_delta","text":"answer"}}""",
            """{"type":"content_block_stop","index":6}""",
            """{"type":"future_event","future_event_field":[false,null]}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","future_terminal":false},"usage":{"future_usage":null}}""",
            """{"type":"message_stop","future_stop":null}""",
        )
        assertTrue(outcome is TurnOutcome.Success)
        sink.emitTerminal(false, false, Usage())
        val message = sink.responseBody()
        val raw = message["content"]!!.jsonArray.first().jsonObject
        assertEquals(event("""{"old":1,"new":2}"""), raw["payload"])
        assertEquals(Json.parseToJsonElement("false"), raw["block_outer"])
        assertEquals(Json.parseToJsonElement("[false]"), raw["delta_outer"])
        assertTrue(raw.containsKey("null_field"))
        assertTrue(raw.containsKey("stop_outer"))
        assertTrue(message.containsKey("future_start"))
        assertTrue(message.containsKey("future_stop"))
        assertEquals(Json.parseToJsonElement("[false,null]"), message["future_event_field"])
        assertEquals(Json.parseToJsonElement("false"), message["future_terminal"])
        assertTrue(message["usage"]!!.jsonObject.containsKey("future_usage"))
    }

    @Test
    fun `initial prose and native signatures are not replaced by empty generated starts`() = runTest {
        val writes = mutableListOf<String>()
        val sink = emitter(writes)
        val outcome = drive(
            sink,
            """{"type":"message_start"}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"thinking","thinking":"initial thought","signature":"native-signature","opaque":null}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":"initial answer","opaque":[false]}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"message_stop"}""",
        )
        assertTrue(outcome is TurnOutcome.Success, "initial prose is real content: $outcome")
        assertEquals("initial answer", (outcome as TurnOutcome.Success).bodyText)
        val starts = decoded(writes).filter { kind(it) == "content_block_start" }
        assertEquals("initial thought", starts[0]["content_block"]!!.jsonObject["thinking"]!!.jsonPrimitive.content)
        assertEquals("native-signature", starts[0]["content_block"]!!.jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("initial answer", starts[1]["content_block"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dropped deltas and synthesized signatures do not inherit another frame scope`() = runTest {
        val writes = mutableListOf<String>()
        val sink = emitter(writes)
        val translator = translator(PassthroughQuirks(providerTag = "kimi", synthesizeSignatures = true))
        val events = listOf(
            event("""{"type":"message_start"}"""),
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}"""),
            event(
                """{"type":"content_block_delta","index":0,"leak":true,"delta":{"type":"text_delta","text":"bad"}}""",
            ),
            event(
                """{"type":"content_block_delta","index":0,"kept":null,"delta":{"type":"thinking_delta","thinking":"thought","opaque":[false]}}""",
            ),
            event("""{"type":"content_block_stop","index":0,"stop_only":false}"""),
            event("""{"type":"message_stop"}"""),
        )
        val outcome = translator.driveTurn(events.asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
        val frames = decoded(writes)
        assertFalse(frames.any { it.containsKey("leak") })
        val signatures = frames.filter { it["delta"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "signature_delta" }
        assertEquals(1, signatures.size)
        assertFalse(signatures.single().containsKey("stop_only"))
        assertFalse(signatures.single().containsKey("kept"))
        assertTrue(frames.any { it.containsKey("kept") })
    }

    @Test
    fun `generic relay cannot emit reserved terminals or invent a block and a torn turn stays failed`() = runTest {
        val writes = mutableListOf<String>()
        val sink = emitter(writes)
        sink.relayEvent(event("""{"type":"message_stop","spoof":true}"""))
        sink.relayEvent(event("""{"type":"content_block_start","index":44,"spoof":true}"""), WireBlockIndex(44))
        sink.relayEvent(event("""{"type":"future_event","index":44,"spoof":true}"""), WireBlockIndex(44))
        assertTrue(writes.isEmpty())
        val outcome = drive(
            sink,
            """{"type":"message_start"}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
        )
        assertTrue(outcome is TurnOutcome.Failure)
        assertFalse(decoded(writes).any { kind(it) == "message_stop" || kind(it) == "message_delta" })
    }

    @Test
    fun `fresh collecting turns cannot inherit native metadata`() = runTest {
        val first = CollectingTerminal("native", { buildJsonObject {} })
        drive(
            first,
            """{"type":"message_start","message":{"private_turn_field":true}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"one"}}""",
            """{"type":"message_stop"}""",
        )
        first.emitTerminal(false, false, Usage())
        assertTrue(first.responseBody().containsKey("private_turn_field"))
        val second = CollectingTerminal("native", { buildJsonObject {} })
        second.addTextBlock("two")
        second.emitTerminal(false, false, Usage())
        assertFalse(second.responseBody().containsKey("private_turn_field"))
    }
}
