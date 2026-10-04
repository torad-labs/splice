// NEW: collected native starts, relay isolation and bounded opaque retention.
package splice.head.wire

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.memory.HeapCapacityException
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.anthropic.PassthroughStreamTranslator
import splice.dialect.anthropic.PassthroughTurnContext

class PassthroughNativeCollectTest {
    private fun event(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun collector(): CollectingTerminal = CollectingTerminal("native", { buildJsonObject {} })

    @Test
    fun `initial prose signature tool id and input survive the collected response`() = runTest {
        val sink = collector()
        val translator = PassthroughStreamTranslator(
            PassthroughTurnContext({ false }, { null }, 180_000, 900_000),
            PassthroughQuirks(providerTag = "native"),
        )
        val events = listOf(
            event("""{"type":"message_start"}"""),
            event(
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"thought","signature":"native-signature","opaque":false}}""",
            ),
            event("""{"type":"content_block_stop","index":0}"""),
            event(
                """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"initial answer","opaque":null}}""",
            ),
            event("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" and delta"}}"""),
            event("""{"type":"content_block_stop","index":1}"""),
            event(
                """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"native-tool-id","name":"Read","input":{"path":"synthetic"},"opaque":[null,false]}}""",
            ),
            event("""{"type":"content_block_stop","index":2}"""),
            event("""{"type":"message_stop"}"""),
        )
        val outcome = translator.driveTurn(events.asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success)
        sink.emitTerminal(true, false, Usage())
        val blocks = sink.responseBody()["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("thought", blocks[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals("native-signature", blocks[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("initial answer and delta", blocks[1]["text"]!!.jsonPrimitive.content)
        assertTrue(blocks[1].containsKey("opaque"))
        assertEquals("native-tool-id", blocks[2]["id"]!!.jsonPrimitive.content)
        assertEquals(event("""{"path":"synthetic"}"""), blocks[2]["input"])
    }

    @Test
    fun `collected generic relay rejects protocol events and nonexistent client block indices`() = runTest {
        val sink = collector()
        sink.relayEvent(event("""{"type":"message_delta","terminal_spoof":true}"""))
        sink.relayEvent(event("""{"type":"future_event","index":33,"index_spoof":true}"""), WireBlockIndex(33))
        sink.addTextBlock("answer")
        sink.emitTerminal(false, false, Usage())
        assertFalse(sink.responseBody().containsKey("terminal_spoof"))
        assertFalse(sink.responseBody().containsKey("index_spoof"))
    }

    @Test
    fun `opaque graphs cannot bypass the response retention bound`() {
        val shared = JsonPrimitive("x".repeat(2048))
        val huge = buildJsonObject { put("opaque", JsonArray(List(10_000) { shared })) }
        assertThrows(HeapCapacityException::class.java) { NativeFields().merge(null, huge) }
        val small = event("""{"opaque":[false,null]}""")
        assertEquals(small, NativeFields().merge(null, small))
    }

    @Test
    fun `many individually small raw blocks share one collection capacity`() = runTest {
        val sink = collector()
        val shared = JsonPrimitive("x".repeat(2048))
        val payload = buildJsonObject {
            put("type", "future")
            put("opaque", JsonArray(List(256) { shared }))
        }
        var admitted = 0
        try {
            repeat(100) {
                val frame = buildJsonObject {
                    put("type", "content_block_start")
                    put("content_block", payload)
                }
                sink.withSourceFrame(frame) { it.openRawBlock(payload) }
                admitted++
            }
        } catch (expected: HeapCapacityException) {
            assertTrue(admitted in 1..99, "the aggregate, not only one frame, must be bounded")
            return@runTest
        }
        throw AssertionError("all raw blocks were retained without a capacity refusal")
    }
}
