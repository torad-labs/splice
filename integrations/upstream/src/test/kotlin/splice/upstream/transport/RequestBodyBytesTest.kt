// NEW: the bytes the transport keeps for one round body, both ways a body arrives. They equal the
// text's UTF-8 byte for byte, and they cost one copy of the body where the buffered encode cost more.
package splice.upstream.transport

import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import splice.core.util.JsonWire
import splice.upstream.RoundBody
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory

private const val WARMUPS = 10
private const val MESSAGES = 420

/** Escapes, a slash, a control character and number spellings, all in plain ASCII. */
private const val ASCII_ACCENT = """ \"quoted\" back\\slash\n tab\t ctl\u0001 slash\/ """

/** The same, plus Latin-1, the euro sign, CJK and an astral emoji. Any char above U+00FF makes the rendered
 *  text a UTF-16 String, the case String.getBytes encodes through a buffer three times the length. */
private const val WIDE_ACCENT = """ café € 漢字 🐉 \"quoted\" back\\slash\n tab\t ctl\u0001 slash\/ """

/** Exactly what RequestBody.bytes did from 99d92575e until this test: a buffer sized from the body,
 *  the body written into it, then the buffer copied out. Kept as the reference the budget rejects. */
private object BufferedReference {
    fun tree(tree: JsonElement): ByteArray =
        ByteArrayOutputStream(JsonWire.byteSize(tree).toInt()).also { JsonWire.write(tree, it) }.toByteArray()

    fun text(text: String): ByteArray =
        ByteArrayOutputStream(JsonWire.byteSize(text).toInt())
            .also { it.write(text.toByteArray(Charsets.UTF_8)) }
            .toByteArray()
}

class RequestBodyAllocationTest {
    private val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        ?: error("JVM allocation counter is required")

    /** About 0.9 MB of synthetic history with [accent] on every line and number spellings up front. */
    private fun body(accent: String): JsonElement = Json.parseToJsonElement(
        (0 until MESSAGES).joinToString(
            separator = ",",
            prefix = """{"model":"synthetic","n":[1.0,1e5,-0,12345678901234567890,0.1e-7],"input":[""",
            postfix = "]}",
        ) { index ->
            val role = if (index % 2 == 0) "user" else "assistant"
            val line = "Synthetic history line $index for transport bytes. ".repeat(44)
            """{"role":"$role","content":"$line$accent"}"""
        },
    )

    @Test
    fun `the kept bytes are the body's UTF-8, from a tree and from text`() {
        listOf(ASCII_ACCENT, WIDE_ACCENT).forEach { accent ->
            val tree = body(accent)
            val text = JsonWire.string(tree)
            val expected = text.toByteArray(Charsets.UTF_8)

            assertArrayEquals(expected, RequestBody(RoundBody.Tree(tree)).bytes, "tree bytes for: $accent")
            assertArrayEquals(expected, RequestBody(RoundBody.Text(text)).bytes, "text bytes for: $accent")
            assertEquals(expected.size.toLong(), RoundBody.Tree(tree).byteSize())
            assertEquals(expected.size.toLong(), RoundBody.Text(text).byteSize())
        }
    }

    /** String.getBytes writes one '?' for a lone surrogate, and JsonWire.byteSize counts it as one byte.
     *  Text an interceptor composed can carry one, so the kept bytes must agree with both. */
    @Test
    fun `a lone surrogate in text encodes as the JVM's one-byte replacement`() {
        val text = "{\"t\":\"before \uD800 after é\"}"
        val kept = RequestBody(RoundBody.Text(text)).bytes

        assertArrayEquals(text.toByteArray(Charsets.UTF_8), kept)
        assertEquals(kept.size.toLong(), RoundBody.Text(text).byteSize())
    }

    @Test
    fun `a tree body costs the transport one copy, where the buffered encode cost two`(reporter: TestReporter) {
        listOf("ascii" to ASCII_ACCENT, "wide" to WIDE_ACCENT).forEach { (name, accent) ->
            val tree = body(accent)
            val size = JsonWire.byteSize(tree)
            assertTrue(size in 850_000L..1_300_000L, "the fixture must be body-sized; it is $size bytes")

            val kept = measure("tree_${name}_kept", reporter) { RequestBody(RoundBody.Tree(tree)).bytes }
            val buffered = measure("tree_${name}_buffered", reporter) { BufferedReference.tree(tree) }

            assertOneCopy(size, kept, buffered)
        }
    }

    @Test
    fun `a text body costs the transport one copy, where the buffered encode cost three`(reporter: TestReporter) {
        listOf("ascii" to ASCII_ACCENT, "wide" to WIDE_ACCENT).forEach { (name, accent) ->
            val text = JsonWire.string(body(accent))
            val size = JsonWire.byteSize(text)
            assertTrue(size in 850_000L..1_300_000L, "the fixture must be body-sized; it is $size bytes")

            val kept = measure("text_${name}_kept", reporter) { RequestBody(RoundBody.Text(text)).bytes }
            val buffered = measure("text_${name}_buffered", reporter) { BufferedReference.text(text) }

            assertOneCopy(size, kept, buffered)
        }
    }

    /** One copy of the body, with a quarter of it to spare for bounded encoder scratch. The buffered
     *  reference must exceed the same budget, so the budget is shown able to fail. */
    private fun assertOneCopy(size: Long, kept: Pair<ByteArray, Long>, buffered: Pair<ByteArray, Long>) {
        val budget = size + size / 4
        assertArrayEquals(buffered.first, kept.first, "both ways must keep the same bytes")
        assertTrue(buffered.second >= budget, "the budget must reject the buffered encode: ${buffered.second}")
        assertTrue(kept.second < budget, "kept=${kept.second}; budget=$budget for a $size-byte body")
    }

    private inline fun measure(name: String, reporter: TestReporter, action: () -> ByteArray): Pair<ByteArray, Long> {
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(WARMUPS) { val _ = action() }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val result = action()
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(thread == Thread.currentThread().threadId(), "the measured call must stay on this thread")
        reporter.publishEntry("request_body_${name}_allocated_bytes", allocated.toString())
        return result to allocated
    }
}
