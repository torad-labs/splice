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
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.anthropic.PassthroughStreamTranslator
import splice.dialect.anthropic.PassthroughTurnContext
import splice.head.compact.CompactStats
import splice.head.testVoice
import splice.head.wire.CollectingTerminal
import splice.head.wire.SseEmitterFactory
import splice.head.wire.StreamWiring
import splice.head.wire.TurnTerminal
import java.nio.file.Path
import kotlin.random.Random

/** What the classifier explained: a sentence a person can read, which the line quotes. */
private const val EXPLANATION = "The request asks for working exploit code."

/** Who declined, as the shared test voice names the head (TestVoice.kt). */
private const val DECLINED = "[splice] The model on claude-test declined to answer"

/** The longest line a person reads (final-design.md section 10). */
private const val MAX_LINE = 240

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

    // Failure-handling slice 2: the person reads the line the turn voice composes for a model's refusal, which quotes
    // the category and explanation the classifier gave (final-design.md section 10, Refused(MODEL)). The old words
    // ("blocked by the upstream safety classifier", "stop_reason=refusal", "Refusal category:", retry advice) are gone
    // with FailureText, and the stop reason stays in the log.
    @Test
    fun `classifier details reach streaming and buffered errors before and after partial output`() = runTest {
        val details = """{"type":"refusal","category":"cyber","explanation":"$EXPLANATION"}"""
        for (partial in listOf(false, true)) {
            val frames = mutableListOf<String>()
            val emitter = SseEmitterFactory().create(
                write = { frames.add(it) },
                model = "claude-splice",
                usagePayload = { buildJsonObject {} },
                streaming = StreamWiring(contentReached = { partial }),
                voice = testVoice,
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

            val buffered = CollectingTerminal("claude-splice", { buildJsonObject {} }, voice = testVoice)
            refuse(buffered, details, partial)
            assertEquals(400, buffered.httpStatus())
            val body = buffered.responseBody()
            assertEquals("error", body["type"]?.jsonPrimitive?.content)
            assertFalse("content" in body)
            assertFalse("stop_reason" in body)
            assertRefusal(body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        }
    }

    /** The refusal line quotes what the classifier said, and invites no re-send: the same request meets the same no. */
    private fun assertRefusal(message: String, category: String = "cyber") {
        assertTrue(message.startsWith("$DECLINED: "), message)
        assertTrue(EXPLANATION.removeSuffix(".") in message, "the explanation reaches the person: $message")
        assertTrue(category in message, "and the category: $message")
        assertFalse("again" in message || "retry" in message, message)
    }

    /** The line a stream=false refusal carrying [details] ends with. */
    private suspend fun bufferedLine(details: String?): String {
        val terminal = CollectingTerminal("claude-splice", { buildJsonObject {} }, voice = testVoice)
        refuse(terminal, details, partial = false)
        return terminal.responseBody().getValue("error").jsonObject.getValue("message").jsonPrimitive.content
    }

    @Test
    fun `an unrecognized category reaches the person like a known one`() = runTest {
        for (category in listOf("cyber", "future_category")) {
            assertRefusal(bufferedLine("""{"category":"$category","explanation":"$EXPLANATION"}"""), category)
        }
    }

    @Test
    fun `a category or an explanation given alone is quoted alone`() = runTest {
        for (category in listOf("cyber", "future_category")) {
            assertEquals("$DECLINED: $category.", bufferedLine("""{"category":"$category"}"""))
            assertEquals("$DECLINED: $category.", bufferedLine("""{"category":"$category","explanation":null}"""))
        }
        assertEquals("$DECLINED: $EXPLANATION", bufferedLine("""{"explanation":"$EXPLANATION"}"""))
    }

    @Test
    fun `a long classifier explanation still leaves one line of at most 240 characters`() = runTest {
        val explanation = "The request asks for working exploit code against a named production system " +
            "and for the steps to keep it hidden from the people who run that system, ".repeat(4) + "in detail."
        val line = bufferedLine("""{"category":"cyber","explanation":"$explanation"}""")
        assertTrue(line.startsWith("$DECLINED: The request asks for working exploit code"), line)
        assertTrue(line.length <= MAX_LINE, "${line.length}: $line")
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
            assertEquals("$DECLINED.", bufferedLine(details), "$details")
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
                val terminal = CollectingTerminal("claude-splice", { buildJsonObject {} }, voice = testVoice)
                val details = buildJsonObject {
                    put("category", value)
                    put("explanation", value)
                }
                refuse(terminal, details.toString(), partial)
                val error = terminal.responseBody().getValue("error").jsonObject
                assertEquals("$DECLINED.", error.getValue("message").jsonPrimitive.content, "$details")
            }
        }
    }

    @Test
    fun `every failure cause uses the error channel after partial output`() = runTest {
        for (cause in everyLeaf) {
            val frames = mutableListOf<String>()
            val emitter = SseEmitterFactory().create(
                write = { frames.add(it) },
                model = "claude-splice",
                usagePayload = { buildJsonObject {} },
                streaming = StreamWiring(contentReached = { true }),
                voice = testVoice,
            )
            emitter.addTextBlock("partial")
            pipeline().finishStream(
                emitter,
                TurnOutcome.Failure(
                    cause = cause,
                    at = Position(Moment.MID_CONTENT, streamBegan = true),
                    evidence = FailureEvidence("failure diagnostic"),
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
