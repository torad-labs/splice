// NEW: V4-457 — streaming JSON encoding for wire strings and bounded-output consumers.
package splice.core.util

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream
import java.util.StringJoiner

// why: ASCII code points fit in one UTF-8 byte; the first non-ASCII value is 2^7.
internal const val UTF8_ASCII_CEILING = 0x80

// why: code points below 2^11 fit in two UTF-8 bytes.
private const val UTF8_TWO_BYTE_CEILING = 0x800

// why: a non-surrogate Basic Multilingual Plane code point occupies three UTF-8 bytes.
private const val UTF8_BMP_WIDTH = 3

// why: a supplementary code point occupies four UTF-8 bytes rather than two UTF-16 code units.
private const val UTF8_SUPPLEMENTARY_WIDTH = 4

// The pinned library owns escaping. Borrow its exact ASCII escape spellings, never a second escape algorithm.
private val WIRE_ESCAPES = Array(UTF8_ASCII_CEILING) { code ->
    val character = code.toChar().toString()
    val quoted = Json.encodeToString(String.serializer(), character)
    quoted.substring(1, quoted.length - 1).takeUnless { it == character }
}

/** Streams borrowed tree nodes while retaining the legacy wire's numeric lexemes and string escaping. */
public object JsonWire {
    // Committed request shapes reach 24 container levels in the Anthropic goldens/request-mfjs-schema.json
    // and 9 in Responses contract/responses-codex-profile.json. 128 leaves >5x schema headroom while
    // bounding every recursive downstream walk. These are fixture measurements, not live-client maxima.
    public const val MAX_REQUEST_DEPTH: Int = 128

    /** Refuses excessive container nesting before parsing; strings and their escapes are not structure. */
    public fun requireRequestNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            when {
                escaped -> escaped = false
                quoted && character == '\\' -> escaped = true
                character == '"' -> quoted = !quoted
                quoted -> Unit
                character in "{[" -> {
                    depth++
                    require(depth <= MAX_REQUEST_DEPTH) { "request JSON nesting exceeds $MAX_REQUEST_DEPTH levels" }
                }
                character in "}]" -> depth--
            }
        }
    }

    public fun string(element: JsonElement): String {
        val wire = StringWire()
        WireTree(wire).tree(element)
        return wire.finish()
    }

    /** Writes with fixed scratch and leaves the caller's stream open. */
    public fun write(element: JsonElement, output: OutputStream) {
        val wire = StreamWire(output)
        WireTree(wire).tree(element)
        wire.drain()
    }

    /** One caller-owned stream encoder. Each value drains before returning; its fixed scratch is reused. */
    public fun encoder(output: OutputStream): Encoder = Encoder(output)

    /** Sequential values share scratch, never pending bytes. This encoder does not close the caller's stream. */
    public class Encoder internal constructor(output: OutputStream) {
        private val wire = StreamWire(output)
        private val tree = WireTree(wire)

        public fun write(element: JsonElement) {
            tree.tree(element)
            wire.drain()
        }
    }

    /** Counts the exact streamed bytes without materializing a wire string or byte array. */
    public fun byteSize(element: JsonElement): Long {
        val counter = WireByteCount()
        write(element, counter)
        return counter.bytes
    }

    /** UTF-8 wire length, including the JVM encoder's single-byte replacement for an unpaired surrogate. */
    public fun byteSize(text: String): Long {
        var bytes = 0L
        var offset = 0
        while (offset < text.length) {
            val point = Character.codePointAt(text, offset)
            offset += Character.charCount(point)
            bytes += when {
                point < UTF8_ASCII_CEILING -> 1
                point < UTF8_TWO_BYTE_CEILING -> 2
                point in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code -> 1
                point <= Character.MAX_VALUE.code -> UTF8_BMP_WIDTH
                else -> UTF8_SUPPLEMENTARY_WIDTH
            }
        }
        return bytes
    }

    private class WireByteCount : OutputStream() {
        var bytes = 0L
            private set

        override fun write(value: Int) {
            bytes += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes += length
        }
    }

    private interface WireText {
        fun raw(text: String, start: Int = 0, end: Int = text.length)
        fun ascii(character: Char)
    }

    /** Both outputs walk the original nodes and retain raw scalar spellings. */
    private class WireTree(private val output: WireText) {
        fun tree(element: JsonElement) {
            when (element) {
                JsonNull -> output.raw("null")
                is JsonObject -> {
                    output.ascii('{')
                    element.entries.forEachIndexed { index, (key, value) ->
                        if (index > 0) output.ascii(',')
                        quoted(key)
                        output.ascii(':')
                        tree(value)
                    }
                    output.ascii('}')
                }
                is JsonArray -> {
                    output.ascii('[')
                    element.forEachIndexed { index, value ->
                        if (index > 0) output.ascii(',')
                        tree(value)
                    }
                    output.ascii(']')
                }
                is JsonPrimitive -> if (element.isString) quoted(element.content) else output.raw(element.content)
            }
        }

        private fun quoted(text: String) {
            output.ascii('"')
            var start = 0
            for (index in text.indices) {
                val escape = WIRE_ESCAPES.getOrNull(text[index].code)
                if (escape != null) {
                    output.raw(text, start, index)
                    output.raw(escape)
                    start = index + 1
                }
            }
            output.raw(text, start, text.length)
            output.ascii('"')
        }
    }

    /** Borrow large spans; the JDK join allocates the final string with its exact size and character width. */
    private class StringWire : WireText {
        private val parts = StringJoiner("")

        // Match the streaming scratch bound while batching punctuation and small scalar spans.
        private val chunk = StringBuilder(WIRE_BUFFER_BYTES)

        override fun raw(text: String, start: Int, end: Int) {
            val length = end - start
            if (length >= WIRE_BUFFER_BYTES) {
                flush()
                parts.add(text.substring(start, end))
            } else {
                if (chunk.length + length > WIRE_BUFFER_BYTES) flush()
                chunk.append(text, start, end)
            }
        }

        override fun ascii(character: Char) {
            if (chunk.length == WIRE_BUFFER_BYTES) flush()
            chunk.append(character)
        }

        fun finish(): String {
            flush()
            return parts.toString()
        }

        private fun flush() {
            if (chunk.isNotEmpty()) {
                parts.add(chunk.toString())
                chunk.setLength(0)
            }
        }
    }

    private class StreamWire(output: OutputStream) : WireText {
        private val utf8 = Utf8Wire(output)

        override fun raw(text: String, start: Int, end: Int) {
            utf8.span(text, start, end)
        }

        override fun ascii(character: Char) {
            utf8.ascii(character)
        }

        fun drain() = utf8.drain()
    }
}
