package splice.head.pipeline

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.FailureTraits
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.anthropic.PassthroughStreamTranslator
import splice.dialect.anthropic.PassthroughTurnContext
import splice.head.compact.CompactStats
import splice.head.wire.CollectingTerminal
import splice.head.wire.SseEmitterFactory
import splice.head.wire.StreamWiring
import splice.head.wire.TurnTerminal
import java.nio.file.Path
import kotlin.random.Random

class ClassifierRefusalDeliveryTest {
    @TempDir
    lateinit var tmp: Path

    private val meta = TurnMeta(
        compact = false,
        reasoning = TurnReasoning(ReasoningDisplay.THINKING, "medium", null, null),
        route = TurnRoute(true, "claude-splice", "claude", null),
    )

    private fun pipeline() = TurnPipeline(CompactStats(tmp.resolve("compact.jsonl")), {}, { it })

    private suspend fun refuse(terminal: TurnTerminal, details: String?, partial: Boolean) {
        val events = buildList {
            add("""{"type":"message_start","message":{"usage":{"input_tokens":17}}}""")
            if (partial) {
                add("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""")
                add(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
                )
                add("""{"type":"content_block_stop","index":0}""")
            }
            val field = details?.let { ",\"stop_details\":$it" }.orEmpty()
            add("""{"type":"message_delta","delta":{"stop_reason":"refusal"$field}}""")
            add("""{"type":"message_stop"}""")
        }.map { Json.parseToJsonElement(it).jsonObject }
        val outcome = PassthroughStreamTranslator(
            PassthroughTurnContext({ false }, { null }, 180_000, 900_000),
            PassthroughQuirks("claude-splice"),
        ).driveTurn(events.asFlow(), terminal)
        pipeline().finishStream(terminal, outcome, meta, elapsedMs = 1)
    }

    @Test
    fun `classifier details reach streaming and buffered errors before and after partial output`() = runTest {
        val details = """{"type":"refusal","category":"cyber","explanation":"Upstream classifier explanation."}"""
        for (partial in listOf(false, true)) {
            val frames = mutableListOf<String>()
            val emitter = SseEmitterFactory().create(
                write = { frames.add(it) },
                model = "claude-splice",
                usagePayload = { buildJsonObject {} },
                streaming = StreamWiring(contentReached = { partial }),
            )
            refuse(emitter, details, partial)
            assertTrue(frames.last().startsWith("event: error\n"), frames.toString())
            assertFalse(emitter.endedCleanly)
            assertTrue(frames.none { it.startsWith("event: message_delta") || it.startsWith("event: message_stop") })
            val deltas = frames.map { Json.parseToJsonElement(it.substringAfter("data: ").trim()).jsonObject }
                .mapNotNull { (it["delta"] as? JsonObject)?.get("text") }
            assertEquals(if (partial) listOf("partial") else emptyList(), deltas.map { it.jsonPrimitive.content })
            val error = Json.parseToJsonElement(frames.last().substringAfter("data: ").trim()).jsonObject
            assertRefusal(error.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals("invalid_request_error", error.getValue("error").jsonObject["type"]?.jsonPrimitive?.content)

            val buffered = CollectingTerminal("claude-splice", { buildJsonObject {} })
            refuse(buffered, details, partial)
            assertEquals(400, buffered.httpStatus())
            val body = buffered.responseBody()
            assertEquals("error", body["type"]?.jsonPrimitive?.content)
            assertFalse("content" in body)
            assertFalse("stop_reason" in body)
            assertRefusal(body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        }
    }

    private fun assertRefusal(message: String) {
        assertTrue(message.contains("claude-splice: generation was blocked by the upstream safety classifier"), message)
        assertTrue(message.contains("stop_reason=refusal"), message)
        assertTrue(message.contains("Refusal category: cyber."), message)
        assertTrue(message.contains("Upstream classifier explanation."), message)
    }

    @Test
    fun `long classifier explanations and unrecognized categories reach the user intact`() = runTest {
        val explanation = "Upstream classifier detail. ".repeat(20) + "End of upstream explanation."
        for (category in listOf("cyber", "future_category")) {
            val terminal = CollectingTerminal("claude-splice", { buildJsonObject {} })
            refuse(terminal, """{"category":"$category","explanation":"$explanation"}""", partial = false)
            val message = terminal.responseBody().getValue("error").jsonObject.getValue("message").jsonPrimitive.content
            assertTrue(message.contains("Refusal category: $category."), message)
            assertTrue(message.contains(explanation), message)
            assertTrue(message.endsWith("Review the request before retrying."), message)
        }
    }

    @Test
    fun `missing null and blank classifier details are reported without inventing a category`() = runTest {
        val variants = listOf(
            null,
            "null",
            "{}",
            """{"category":null,"explanation":null}""",
            """{"category":" ","explanation":""}""",
        )
        for (details in variants) {
            val terminal = CollectingTerminal("claude-splice", { buildJsonObject {} })
            refuse(terminal, details, partial = false)
            val message = terminal.responseBody().getValue("error").jsonObject.getValue("message").jsonPrimitive.content
            assertTrue(message.contains("did not provide a refusal category or explanation"), message)
            assertTrue(message.contains("Review the request before retrying"), message)
            assertFalse(message.contains("Refusal category:"), message)
            assertFalse(message.contains("null"), message)
        }
    }

    @Test
    fun `non-string classifier metadata never becomes an invented category or explanation`() = runTest {
        val random = Random(0x5AFE)
        val malformed = listOf(
            JsonPrimitive(true),
            JsonPrimitive(false),
            JsonArray(emptyList()),
            buildJsonObject { put("unexpected", JsonPrimitive("metadata")) },
        ) + List(32) { JsonPrimitive(random.nextLong()) }
        for (value in malformed) {
            for (partial in listOf(false, true)) {
                val terminal = CollectingTerminal("claude-splice", { buildJsonObject {} })
                val details = buildJsonObject {
                    put("category", value)
                    put("explanation", value)
                }
                refuse(terminal, details.toString(), partial)
                val error = terminal.responseBody().getValue("error").jsonObject
                val message = error.getValue("message").jsonPrimitive.content
                assertTrue(message.contains("did not provide a refusal category or explanation"), message)
                assertFalse(message.contains("Refusal category:"), message)
            }
        }
    }

    @Test
    fun `every failure cause uses the error channel after partial output`() = runTest {
        for (cause in FailureCause.entries) {
            for (deterministic in listOf(false, true)) {
                val frames = mutableListOf<String>()
                val emitter = SseEmitterFactory().create(
                    write = { frames.add(it) },
                    model = "claude-splice",
                    usagePayload = { buildJsonObject {} },
                    streaming = StreamWiring(contentReached = { true }),
                )
                emitter.addTextBlock("partial")
                pipeline().finishStream(
                    emitter,
                    TurnOutcome.Failure(
                        "failure diagnostic",
                        cause,
                        FailurePhase.MID_OUTPUT,
                        traits = FailureTraits(deterministic = deterministic),
                    ),
                    meta,
                    elapsedMs = 1,
                )
                assertTrue(frames.last().startsWith("event: error\n"), "$cause: $frames")
                assertFalse(emitter.endedCleanly, "$cause")
                assertTrue(
                    frames.none { it.startsWith("event: message_delta") || it.startsWith("event: message_stop") },
                )
                assertTrue(frames.filter { it.contains("text_delta") }.all { it.contains("\"text\":\"partial\"") })
            }
        }
    }
}
