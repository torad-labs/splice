package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.parse.AnthropicParse
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.upstream.TurnSignals
import java.nio.file.Path

class ResponsesMessageFrontierTest(@param:TempDir private val state: Path) {
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })
    private val opening = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."}]}""",
    )

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `index-free completion uses the identity of prose streamed after reasoning`(indexedDelta: Boolean) = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(opening, false, "session")
        val before = DeliveryFrames.reasoning("rs_before_message")
        val message = DeliveryFrames.message("msg_unindexed", "Hello")
        val after = DeliveryFrames.reasoning("rs_after_message")
        val call = DeliveryFrames.exec()
        val sink = MessageWireProbe()
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.done(before, 0),
                delta("Hello", "msg_unindexed", if (indexedDelta) 1 else null),
                DeliveryFrames.done(message, null),
                DeliveryFrames.done(after, 2),
                DeliveryFrames.done(call, 3),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        val success = checkNotNull(outcome as? TurnOutcome.Success)
        assertEquals("Hello", sink.text, "completion must not invent another message at a different index")
        assertEquals(1, sink.textBlocks.size)
        assertEquals("Hello", success.text.bodyText)
        assertEquals(listOf(before, message, after), success.handoffs.nativePrefix)
        assertEquals(emptyList<JsonObject>(), success.handoffs.nativeAfterExec)
        assertEquals(0, sink.opaqueBlocks)
        assertReplay(provider, built.requestBody, "Hello", listOf(before, after))
        assertReplay(DeliveryProvider(state), built.requestBody, "Hello", listOf(before, after))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Hello", "He|llo", "H|e|l|l|o"])
    fun `fully observed completed messages absorb repeated identified delta chunks`(repeated: String) = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(opening, false, "session")
        val message = DeliveryFrames.message("msg_observed", "Hello")
        val reason = DeliveryFrames.reasoning("rs_after_repeated")
        val frames = buildList {
            add(delta("Hello", "msg_observed", 0))
            add(DeliveryFrames.done(message, 0))
            repeated.split('|').forEach { add(delta(it, "msg_observed", 0)) }
            add(DeliveryFrames.done(reason, 1))
            add(DeliveryFrames.done(DeliveryFrames.exec(), 2))
            add(DeliveryFrames.terminal())
        }
        val sink = MessageWireProbe()
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(frames.asFlow(), sink)
        assertTrue(outcome is TurnOutcome.Success, "a repeated delta cannot fail an already completed answer")
        val success = checkNotNull(outcome as? TurnOutcome.Success)
        assertEquals("Hello", sink.text)
        assertEquals(1, sink.textBlocks.size)
        assertEquals("Hello", success.text.bodyText)
        assertEquals(listOf(message, reason), success.handoffs.nativePrefix)
        assertEquals(0, sink.opaqueBlocks)
        assertReplay(provider, built.requestBody, "Hello", listOf(reason))
        assertReplay(DeliveryProvider(state), built.requestBody, "Hello", listOf(reason))
    }

    @Test
    fun `a new identified message at a reused index retains its real completion suffix`() = runTest {
        val provider = DeliveryProvider(state)
        val built = provider.buildTurn(opening, false, "session")
        val first = DeliveryFrames.message("msg_first", "Hello")
        val second = DeliveryFrames.message("msg_second", "World")
        val reason = DeliveryFrames.reasoning("rs_after_new_message")
        val sink = MessageWireProbe()
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                delta("Hello", "msg_first", 0),
                DeliveryFrames.done(first, 0),
                added(second, 0),
                delta("Wor", "msg_second", 0),
                DeliveryFrames.done(second, 0),
                delta("ld", "msg_second", 0),
                DeliveryFrames.done(reason, 1),
                DeliveryFrames.done(DeliveryFrames.exec(), 2),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        val success = checkNotNull(outcome as? TurnOutcome.Success)
        assertEquals("HelloWorld", sink.text)
        assertEquals(2, sink.textBlocks.size)
        assertEquals(listOf(first, second, reason), success.handoffs.nativePrefix)
        assertEquals(0, sink.opaqueBlocks)
        assertReplay(DeliveryProvider(state), built.requestBody, "HelloWorld", listOf(reason))
    }

    private fun assertReplay(
        provider: DeliveryProvider,
        seed: JsonObject,
        text: String,
        reasons: List<JsonObject>,
    ) {
        val client = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
                """{"role":"assistant","content":${JsonPrimitive(text)}},{"role":"user","content":"Continue."}]}""",
        )
        val input = provider.buildTurn(client, false, "session").requestBody.getValue("input").jsonArray
        val initial = seed.getValue("input").jsonArray
        assertEquals(initial, JsonArray(input.take(initial.size)), "configured seed metadata remains byte-exact")
        val tail = input.drop(initial.size).map { it.jsonObject }
        val expected = if (reasons.size == 2) {
            listOf(JsonScalars.str(reasons.first(), "id"), text, JsonScalars.str(reasons.last(), "id"), "user")
        } else {
            listOf(text, JsonScalars.str(reasons.single(), "id"), "user")
        }
        assertEquals(expected, tail.map(::label), "reasoning must retain its actual delivered prose boundary")
        assertEquals(reasons, tail.filter { JsonScalars.str(it, "type") == "reasoning" })
    }

    private fun label(item: JsonObject): String? = when {
        JsonScalars.str(item, "type") == "reasoning" -> JsonScalars.str(item, "id")
        JsonScalars.str(item, "role") == "assistant" -> ResponsesMessageText.text(item)
        else -> JsonScalars.str(item, "role")
    }

    private fun delta(text: String, id: String, index: Int?): JsonObject = buildJsonObject {
        put("type", "response.output_text.delta")
        put("item_id", id)
        index?.let { put("output_index", it) }
        put("delta", text)
    }

    private fun added(item: JsonObject, index: Int): JsonObject = buildJsonObject {
        put("type", "response.output_item.added")
        put("output_index", index)
        put("item", item)
    }
}
