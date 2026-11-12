package splice.app.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import java.nio.file.Files
import java.nio.file.Path

/** Native source order is exercised through the real reader, provider cache, bridge and callback HTTP turns. */
@Timeout(90)
class CodeModeOrderedContinuityTest {
    @Test
    fun `an owned callback preserves reasoning on both sides of its echoed preface`(@TempDir tmp: Path) = runBlocking {
        val upstream = OrderedContinuityUpstream()
        val gateway = OrderedContinuityGateway(tmp, upstream)
        try {
            gateway.start()
            val callback = gateway.first()
            assertEquals(
                listOf("text", "tool_use"),
                callback.map { it.jsonObject.getValue("type").jsonPrimitive.content }.distinct(),
            )
            assertEquals(
                OrderedContinuityItems.PREFACE,
                callback.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "text" }
                    .joinToString("") { it.getValue("text").jsonPrimitive.content },
            )
            assertFalse(upstream.terminalReleased, "the source callback must finish while its reader is live")
            upstream.releaseTerminal()
            gateway.finish(callback)

            assertSource(upstream.inputs.last())
            assertNoOpaqueBlocks(gateway)
        } finally {
            upstream.releaseTerminal()
            gateway.close()
            upstream.close()
        }
    }

    @Test
    fun `a completed source restores ordered native history after restart without cache or client preface`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = OrderedContinuityUpstream()
        val gateway = OrderedContinuityGateway(tmp, upstream)
        try {
            gateway.start()
            val callback = gateway.first()
            upstream.releaseTerminal()
            val answer = gateway.finish(callback)
            assertFalse(Files.exists(tmp.resolve("reasoning-1")), "the restarted provider has no reasoning cache")
            gateway.restart()
            val resumed = gateway.next(callback, answer)
            assertTrue(resumed.none { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" })

            assertSource(upstream.inputs.last())
            assertNoOpaqueBlocks(gateway)
        } finally {
            upstream.releaseTerminal()
            gateway.close()
            upstream.close()
        }
    }

    private fun assertSource(input: JsonArray) {
        val calls = input.map { it.jsonObject }.filter { it["call_id"]?.jsonPrimitive?.content == "ordered-exec" }
        assertEquals(
            listOf("custom_tool_call", "custom_tool_call_output"),
            calls.map {
                it.getValue("type").jsonPrimitive.content
            },
        )
        val at = input.indexOf(calls.first())
        assertTrue(at >= 4, input.toString())
        val expected = JsonArray(
            listOf(
                OrderedContinuityItems.firstReasoning,
                ResponsesAssistantText.item(OrderedContinuityItems.PREFACE_A, AssistantPhase.COMMENTARY),
                OrderedContinuityItems.lastReasoning,
                ResponsesAssistantText.item(OrderedContinuityItems.PREFACE_B, AssistantPhase.COMMENTARY),
                OrderedContinuityItems.exec,
            ),
        )
        assertEquals(expected, JsonArray(input.subList(at - 4, at + 1)), "source items must retain their native order")
        assertEquals(calls.last(), input[at + 1], "the saved output belongs immediately after its exec")
        assertTrue(
            calls.last().getValue("output").jsonPrimitive.content.trimEnd().endsWith("\nOutput:\nordered-result"),
        )
        listOf(OrderedContinuityItems.firstReasoning, OrderedContinuityItems.lastReasoning).forEach { reasoning ->
            assertEquals(1, input.count { it == reasoning }, "each native reasoning item must be retained exactly once")
        }
    }

    private fun assertNoOpaqueBlocks(gateway: OrderedContinuityGateway) {
        val blocks = gateway.clientContent.flatten().map { it as JsonObject }
        assertTrue(blocks.none { it["type"]?.jsonPrimitive?.content == "redacted_thinking" })
    }
}
