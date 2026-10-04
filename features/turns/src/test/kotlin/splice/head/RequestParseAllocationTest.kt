// NEW: per-stage current-thread allocation across the whole inbound request path, on a synthetic body
// shaped like a Claude Code turn at about 190k tokens of context. RequestBodyReaderTest already bounds
// bytes -> String and RoundSerializationTest already bounds tree -> wire; the parse between them was the
// one unmeasured stage, and it is where the path's remaining copies are.
package splice.head

import com.sun.management.ThreadMXBean
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import splice.core.parse.lenientJson
import splice.core.util.JsonWire
import splice.core.wire.AnthropicRequest
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory

private const val WARMUPS = 10
private const val READ_TIMEOUT_MS = 5_000L
private const val MESSAGES = 230
private const val BLOCKS = 6
private const val TOOLS = 20
private const val SCHEMA_FIELDS = 8

/** Today's parse keeps the raw tree AND a typed graph that re-copies every opaque tool schema and tool
 *  input. One representation plus decoder scratch is well under half of this. */
private const val PARSE_BUDGET = 9_000_000L

/** Counts what a byte sink would receive without keeping any of it, so a streamed encode is measured
 *  against the same work the transport does and not against a retained buffer. */
private class CountingSink : OutputStream() {
    var bytes = 0L
        private set

    override fun write(value: Int) {
        bytes += 1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        bytes += length
    }
}

class RequestParseAllocationTest {
    private val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        ?: error("JVM allocation counter is required")

    /** Inline so a measured stage may suspend, which the inbound read does. */
    private inline fun <T> measure(name: String, reporter: TestReporter, action: () -> T): Pair<T, Long> {
        repeat(WARMUPS) { val _ = action() }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val result = action()
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(thread == Thread.currentThread().threadId(), "the measured stage must stay on this thread")
        reporter.publishEntry("stage_${name}_allocated_bytes", allocated.toString())
        return result to allocated
    }

    @Test
    fun `the inbound path's stages each stay within their allocation budget`(reporter: TestReporter) = runTest {
        bean.isThreadAllocatedMemoryEnabled = true
        val text = syntheticTurn()
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size in 900_000..1_200_000, "fixture is ${bytes.size} bytes; Claude Code sends 0.5-1.2 MB")
        val reader = RequestBodyReader(READ_TIMEOUT_MS)

        val decoded = measure("reader", reporter) {
            reader.receiveBodyBounded(ByteReadChannel(bytes), bytes.size.toLong(), bytes.size)
        }
        val tree = measure("tree", reporter) { lenientJson.parseToJsonElement(text).jsonObject }
        val typed = measure("typed", reporter) {
            lenientJson.decodeFromJsonElement(AnthropicRequest.serializer(), tree.first)
        }
        val parsed = measure("parse", reporter) { AnthropicBodyParse().parse(text).getOrThrow() }
        val wire = measure("wire_string", reporter) { JsonWire.string(tree.first) }
        // What the transport actually needs: UTF-8 bytes. Today it gets them from the String above.
        val encoded = measure("wire_string_to_bytes", reporter) {
            JsonWire.string(tree.first).toByteArray(Charsets.UTF_8)
        }
        val sunk = measure("wire_stream", reporter) { CountingSink().also { JsonWire.write(tree.first, it) }.bytes }

        assertEquals(text, decoded.first.text)
        assertEquals(MESSAGES, typed.first.messages.size)
        assertEquals(TOOLS, typed.first.tools.size)
        assertEquals(text, wire.first)
        assertEquals(bytes.size.toLong(), sunk.first)
        assertArrayEquals(bytes, encoded.first)
        // Law 8's boundary: a streamed encode is only usable in place of the String if its bytes are identical.
        assertArrayEquals(
            encoded.first,
            ByteArrayOutputStream(bytes.size).also { JsonWire.write(tree.first, it) }.toByteArray(),
            "streamed and String encodes must agree byte for byte",
        )
        assertEquals(tree.first, parsed.first.raw)

        reporter.publishEntry("stage_path_allocated_bytes", (decoded.second + parsed.second + wire.second).toString())
        // Each budget is the measured cost of ONE representation at this body size plus its decoder scratch.
        // The parse budget is the one that moves: it holds both the raw tree and the typed graph today.
        assertTrue(decoded.second < 3_200_000, "reader=${decoded.second}; budget=3200000")
        assertTrue(wire.second < 2_400_000, "wire_string=${wire.second}; budget=2400000")
        assertTrue(sunk.second < wire.second, "a streamed encode must not cost more than a retained String")
        assertTrue(parsed.second < PARSE_BUDGET, "parse=${parsed.second}; budget=$PARSE_BUDGET")
    }

    /** A turn shaped like Claude Code's: one system prompt, a tool catalogue whose schemas stay opaque to the
     *  typed view, and a conversation of text, tool_use and tool_result blocks. The node COUNT is what the
     *  parse pays for, so the fixture spreads its bytes across blocks instead of into one long string. */
    private fun syntheticTurn(): String = JsonWire.string(
        buildJsonObject {
            put("model", "synthetic-sonnet")
            put("stream", true)
            put("max_tokens", 8_000)
            put("system", "You are a synthetic assistant used only to measure allocation. ".repeat(40))
            put("tools", JsonArray(List(TOOLS, ::tool)))
            put("messages", JsonArray(List(MESSAGES, ::message)))
        },
    )

    private fun tool(index: Int): JsonObject = buildJsonObject {
        put("name", "synthetic_tool_$index")
        put("description", "A synthetic tool with a schema the typed view keeps opaque. ".repeat(6))
        put(
            "input_schema",
            buildJsonObject {
                put("type", "object")
                put("properties", JsonObject((0 until SCHEMA_FIELDS).associate { "field_$it" to field(index, it) }))
                put("required", buildJsonArray { repeat(SCHEMA_FIELDS) { add(JsonPrimitive("field_$it")) } })
            },
        )
    }

    private fun field(tool: Int, index: Int): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", "Synthetic field $index of tool $tool. ".repeat(4))
    }

    private fun message(index: Int): JsonObject = buildJsonObject {
        val assistant = index % 2 == 1
        put("role", if (assistant) "assistant" else "user")
        put("content", JsonArray(List(BLOCKS) { block(index, it, assistant) }))
    }

    private fun block(message: Int, index: Int, assistant: Boolean): JsonObject = when {
        index > 0 -> buildJsonObject {
            put("type", "text")
            put("text", "Synthetic conversation text, turn $message block $index. ".repeat(14))
        }
        assistant -> buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_${message}_0")
            put("name", "synthetic_tool_${message % TOOLS}")
            put(
                "input",
                buildJsonObject {
                    put("field_0", "synthetic argument for turn $message. ".repeat(8))
                    put("field_1", "a second opaque argument. ".repeat(8))
                },
            )
        }
        else -> buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", "toolu_${message - 1}_0")
            put("content", "Synthetic tool output line for turn $message. ".repeat(20))
        }
    }
}
