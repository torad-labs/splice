// NEW: V4-457 — pinned JSON bytes and UTF-8 length for strings and stream consumers.
package splice.core.util

import com.sun.management.ThreadMXBean
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.management.ManagementFactory

class JsonWireTest {
    @Test
    fun `wire strings preserve non ASCII escaping nested tool blocks and scalar spellings`() {
        val golden = """{"text":"café 🐉\nquote \" and slash \\","tool":{"input":{"nested":[{"tab":"\t"}],"number":1.0,"null":null}}}"""
        val tree = Json.parseToJsonElement(golden)
        assertEquals(golden, JsonWire.string(tree))
        val output = ByteArrayOutputStream()
        JsonWire.write(tree, output)
        assertArrayEquals(golden.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertEquals(golden.toByteArray(Charsets.UTF_8).size.toLong(), JsonWire.byteSize(tree))
    }

    @Test
    fun `stream consumers keep the same bytes as the legacy tree wire including malformed surrogates`() {
        for (text in listOf("ASCII", "café", "🧪", "\uD800", "\uDC00", "\uD800x\uDC00", "\u0000\n\t\"\\")) {
            val tree = JsonPrimitive(text)
            val expected = tree.toString().toByteArray(Charsets.UTF_8)
            val output = ByteArrayOutputStream()
            JsonWire.write(tree, output)
            assertArrayEquals(expected, output.toByteArray(), text)
            assertArrayEquals(expected, JsonWire.string(tree).toByteArray(Charsets.UTF_8), text)
        }
    }

    @Test
    fun `raw numeric lexemes remain byte identical`() {
        for (raw in listOf("1e2", "1.00", "-0", "184467440737095516160", "1e-300")) {
            val tree = Json.parseToJsonElement(raw)
            assertEquals(raw, JsonWire.string(tree))
            val output = ByteArrayOutputStream()
            JsonWire.write(tree, output)
            assertArrayEquals(raw.toByteArray(Charsets.UTF_8), output.toByteArray())
        }
    }

    @Test
    fun `wire byte contracts retain array order exponent case empty containers and escaped keys`() {
        val golden = """{"quote\\\"\\n":[1,2,1E2,true,false,{},[]],"empty":{}}"""
        val tree = Json.parseToJsonElement(golden)
        assertEquals(golden, JsonWire.string(tree))
        val output = ByteArrayOutputStream()
        JsonWire.write(tree, output)
        assertArrayEquals(golden.toByteArray(Charsets.UTF_8), output.toByteArray())
        assertEquals(golden.toByteArray(Charsets.UTF_8).size.toLong(), JsonWire.byteSize(tree))
    }

    @Test
    fun `stream allocation stays bounded for a million character borrowed string`(reporter: TestReporter) {
        val fixtures = listOf(
            "ascii" to JsonPrimitive("x".repeat(1_000_000)),
            "escaped" to JsonPrimitive("x\n".repeat(500_000)),
            "unicode" to JsonPrimitive("é🧪".repeat(333_333)),
        )
        for ((kind, tree) in fixtures) {
            val expected = tree.toString().toByteArray(Charsets.UTF_8).size.toLong()
            OutputStream.nullOutputStream().use { output ->
                val allocated = allocated { JsonWire.write(tree, output) }
                reporter.publishEntry("${kind}_stream_allocated_bytes", allocated.toString())
                assertTrue(allocated < 250_000, "${kind}_stream_allocated_bytes=$allocated")
                val countAllocated = allocated { assertEquals(expected, JsonWire.byteSize(tree)) }
                reporter.publishEntry("${kind}_count_allocated_bytes", countAllocated.toString())
                assertTrue(countAllocated < 250_000, "${kind}_count_allocated_bytes=$countAllocated")
            }
        }
    }

    private fun allocated(action: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(8) { action() }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        action()
        return bean.getThreadAllocatedBytes(thread) - before
    }

    @Test
    fun `stream escaping and UTF8 spans remain exact at scratch buffer boundaries`() {
        val controls = (0..127).map(Int::toChar).joinToString("")
        val texts = listOf(controls) + listOf(-2, -1, 0).flatMap { offset ->
            // The opening quote takes one byte; sweep both sides of the actual scratch boundary.
            val prefix = "x".repeat(WIRE_BUFFER_BYTES - 1 + offset)
            listOf(
                prefix + "🧪é\u0001\n\"\\" + "y".repeat(WIRE_BUFFER_BYTES),
                prefix + "\uD800\u0001\"\uDC00",
            )
        }
        for (text in texts) {
            val tree = JsonPrimitive(text)
            val output = ByteArrayOutputStream()
            JsonWire.write(tree, output)
            val expected = tree.toString().toByteArray(Charsets.UTF_8)
            assertArrayEquals(expected, output.toByteArray())
            assertEquals(expected.size.toLong(), JsonWire.byteSize(tree))
        }
    }

    @Test
    fun `a stream stays caller owned and its write failure propagates unchanged`() {
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() {
                closed = true
            }
        }
        JsonWire.write(JsonPrimitive("synthetic"), output)
        assertFalse(output.closed)
        output.close()
        assertTrue(output.closed)
        val failure = IOException("synthetic output failure")
        val broken = object : OutputStream() {
            override fun write(value: Int) {
                throw failure
            }
        }
        assertSame(failure, assertThrows(IOException::class.java) { JsonWire.write(JsonPrimitive("x"), broken) })
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `unquoted non ASCII literals use UTF8 rather than one byte per character`() {
        val literal = JsonUnquotedLiteral("é")
        val expected = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        val output = ByteArrayOutputStream()
        JsonWire.write(literal, output)
        assertArrayEquals(expected, output.toByteArray())
        assertArrayEquals(expected, JsonWire.string(literal).toByteArray(Charsets.UTF_8))
        assertEquals(expected.size.toLong(), JsonWire.byteSize(literal))
    }

    @Test
    fun `wire byte counts match the UTF8 encoder without allocating the wire byte array`() {
        for (text in listOf("", "ASCII", "café", "🧪", "\uD800", "\uDC00", "\uD800x\uDC00")) {
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), JsonWire.byteSize(text), text)
        }
    }
}
