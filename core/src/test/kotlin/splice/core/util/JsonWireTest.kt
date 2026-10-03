// NEW: V4-457 — pinned JSON bytes and UTF-8 length for strings and stream consumers.
package splice.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class JsonWireTest {
    @Test
    fun `wire strings preserve non ASCII escaping nested tool blocks and scalar spellings`() {
        val golden = """{"text":"café 🐉\nquote \" and slash \\","tool":{"input":{"nested":[{"tab":"\t"}],"number":1.0,"null":null}}}"""
        val tree = Json.parseToJsonElement(golden)
        assertEquals(golden, JsonWire.string(tree))
        val output = ByteArrayOutputStream()
        JsonWire.write(tree, output)
        assertArrayEquals(golden.toByteArray(Charsets.UTF_8), output.toByteArray())
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
    }

    @Test
    fun `wire byte counts match the UTF8 encoder without allocating the wire byte array`() {
        for (text in listOf("", "ASCII", "café", "🧪", "\uD800", "\uDC00", "\uD800x\uDC00")) {
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), JsonWire.byteSize(text), text)
        }
    }
}
