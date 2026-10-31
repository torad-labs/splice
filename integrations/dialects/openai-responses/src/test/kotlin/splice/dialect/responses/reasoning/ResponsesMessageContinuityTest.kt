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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.index.WireBlockIndex
import splice.core.parse.AnthropicParse
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.upstream.ReanchorRound
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink
import java.nio.file.Path

internal class MessageWireProbe(private val delivered: DeliverySink = DeliverySink()) : WireSink by delivered {
    val text: String get() = delivered.text.toString()
    val opaqueBlocks: Int get() = delivered.opaqueBlocks
    val textBlocks = mutableListOf<WireBlockIndex>()
    val toolNames = mutableListOf<String>()
    val toolInput = StringBuilder()

    override suspend fun openText(): WireBlockIndex = delivered.openText().also { textBlocks += it }
    override suspend fun openTool(id: String, name: String): WireBlockIndex {
        toolNames += name
        return delivered.openTool(id, name)
    }
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        toolInput.append(partialJson)
        delivered.inputJsonDelta(index, partialJson)
    }
}

class ResponsesMessageContinuityTest(@param:TempDir private val state: Path) {
    private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })
    private val opening = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."}]}""",
    )
    private val heads = DeliveryHeads(state)

    @AfterEach
    fun stopHeads() = heads.stopAll()

    @Test
    fun `late delta after eager completion does not duplicate a client row or later reasoning position`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = MessageWireProbe()
        val message = DeliveryFrames.message("msg_first", "Hello")
        val reason = DeliveryFrames.reasoning("rs_after_late")
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.done(message),
                delta("Hello", 0, "msg_first"),
                DeliveryFrames.done(reason, 1),
                DeliveryFrames.done(DeliveryFrames.exec(), 2),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        val success = checkNotNull(outcome as? TurnOutcome.Success)
        assertEquals("Hello", sink.text)
        assertEquals(1, sink.textBlocks.size, "the client must not receive a second text row for the same message")
        assertEquals("Hello", success.text.bodyText)
        assertEquals(listOf(message, reason), success.handoffs.nativePrefix)
        assertEquals(0, sink.opaqueBlocks)
        for (view in heads.thenRestarted(provider)) {
            assertEquals(listOf("developer", "user", "Hello", "rs_after_late", "user"), project(input(view, "Hello")))
        }
    }

    @Test
    fun `late remaining chunks reconcile with the suffix already delivered at completion`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = MessageWireProbe()
        val message = DeliveryFrames.message("msg_chunked", "Hello")
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                delta("He", 0, "msg_chunked"),
                DeliveryFrames.done(message),
                delta("l", 0, "msg_chunked"),
                delta("lo", 0, "msg_chunked"),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success, "$outcome")
        assertEquals("Hello", sink.text)
        assertEquals(1, sink.textBlocks.size)
        assertEquals(0, sink.opaqueBlocks)
    }

    @Test
    fun `a distinct message at the same index still delivers identical legitimate text`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = MessageWireProbe()
        val first = DeliveryFrames.message("msg_first", "Hello")
        val second = DeliveryFrames.message("msg_second", "Hello")
        val reason = DeliveryFrames.reasoning("rs_after_reuse")
        val outcome = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                DeliveryFrames.done(first),
                added(second, 0),
                delta("Hello", 0, "msg_second"),
                DeliveryFrames.done(second),
                DeliveryFrames.done(reason, 1),
                DeliveryFrames.terminal(),
            ).asFlow(),
            sink,
        )
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals("HelloHello", sink.text)
        assertEquals(2, sink.textBlocks.size)
        assertEquals(0, sink.opaqueBlocks)
        assertEquals(
            listOf("developer", "user", "HelloHello", "rs_after_reuse", "user"),
            project(input(heads.restart(), sink.text)),
        )
    }

    @Test
    fun `torn interleaved messages resume actual delivery order with following reasoning intact`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = MessageWireProbe()
        val left = DeliveryFrames.message("msg_left", "Left")
        val right = DeliveryFrames.message("msg_right", "Right")
        val reason = DeliveryFrames.reasoning("rs_after_delivery")
        val torn = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                delta("Right", 1, "msg_right"),
                delta("Left", 0, "msg_left"),
                DeliveryFrames.done(left),
                DeliveryFrames.done(right, 1),
                DeliveryFrames.done(reason, 2),
            ).asFlow(),
            sink,
        )
        val failure = checkNotNull(torn as? TurnOutcome.Failure)
        assertEquals("RightLeft", sink.text)
        assertEquals(listOf(left, right, reason), checkNotNull(failure.partial).nativePrefix)
        val continuation = checkNotNull(provider.reanchorPolicy(built.meta))
            .continuationForFailure(ReanchorRound(built.requestBody, failure, 0))
        assertNotNull(continuation, "complete delivered prose has a provable recovery in delivery order")
        val added = checkNotNull(continuation).getValue("input").jsonArray
            .drop(built.requestBody.getValue("input").jsonArray.size).dropLast(1).map { it.jsonObject }
        assertEquals(listOf("RightLeft", "rs_after_delivery"), project(added))
        for (view in heads.thenRestarted(provider)) {
            assertEquals(
                listOf("developer", "user", "RightLeft", "rs_after_delivery", "user"),
                project(input(view, sink.text)),
            )
        }
        assertEquals(0, sink.opaqueBlocks)
    }

    @Test
    fun `recovery and restart preserve reasoning between separately delivered messages`() = runTest {
        val provider = heads.start()
        val built = provider.buildTurn(opening, false, "session")
        val sink = MessageWireProbe()
        val left = DeliveryFrames.message("msg_left", "Left")
        val right = DeliveryFrames.message("msg_right", "Right")
        val middle = DeliveryFrames.reasoning("rs_middle")
        val after = DeliveryFrames.reasoning("rs_after")
        val torn = provider.streamTranslator(built.meta, signals).driveTurn(
            listOf(
                delta("Left", 0, "msg_left"),
                DeliveryFrames.done(left),
                DeliveryFrames.done(middle, 1),
                delta("Right", 2, "msg_right"),
                DeliveryFrames.done(right, 2),
                DeliveryFrames.done(after, 3),
            ).asFlow(),
            sink,
        )
        val failure = checkNotNull(torn as? TurnOutcome.Failure)
        val continuation = checkNotNull(provider.reanchorPolicy(built.meta))
            .continuationForFailure(ReanchorRound(built.requestBody, failure, 0))
        val added = checkNotNull(continuation).getValue("input").jsonArray
            .drop(built.requestBody.getValue("input").jsonArray.size).dropLast(1).map { it.jsonObject }
        assertEquals(listOf("Left", "rs_middle", "Right", "rs_after"), project(added))
        assertEquals(listOf(left, middle, right, after), checkNotNull(failure.partial).nativePrefix)
        for (view in heads.thenRestarted(provider)) {
            assertEquals(
                listOf("developer", "user", "Left", "rs_middle", "Right", "rs_after", "user"),
                project(input(view, sink.text)),
            )
        }
        assertEquals("LeftRight", sink.text)
        assertEquals(0, sink.opaqueBlocks)
    }

    private fun delta(text: String, index: Int, id: String): JsonObject =
        JsonObject(DeliveryFrames.delta(text, index) + ("item_id" to JsonPrimitive(id)))

    private fun added(item: JsonObject, index: Int): JsonObject = buildJsonObject {
        put("type", "response.output_item.added")
        put("output_index", index)
        put("item", item)
    }

    private fun input(provider: DeliveryProvider, text: String): List<JsonObject> {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":"Check the answer."},""" +
                """{"role":"assistant","content":${JsonPrimitive(text)}},{"role":"user","content":"Continue."}]}""",
        )
        return provider.buildTurn(body, false, "session").requestBody.getValue("input").jsonArray.map { it.jsonObject }
    }

    /** Adjacent prose may coalesce; reasoning remains an exact boundary between its surrounding bytes. */
    private fun project(items: List<JsonObject>): List<String> {
        val result = mutableListOf<String>()
        var previousText = false
        for (item in items) {
            val prose = JsonScalars.str(item, "role") == "assistant"
            val value = when {
                prose -> JsonScalars.str(item, "content")
                    ?: (item["content"] as? JsonArray).orEmpty().joinToString("") {
                        JsonScalars.str(it as? JsonObject, "text").orEmpty()
                    }
                JsonScalars.str(item, "type") == "reasoning" -> checkNotNull(JsonScalars.str(item, "id"))
                else -> checkNotNull(JsonScalars.str(item, "role"))
            }
            if (prose && previousText) result[result.lastIndex] += value else result += value
            previousText = prose
        }
        return result
    }
}
