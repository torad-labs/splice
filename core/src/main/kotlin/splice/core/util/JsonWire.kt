// NEW: V4-457 — streaming JSON encoding for wire strings and bounded-output consumers.
package splice.core.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

// why: ASCII code points fit in one UTF-8 byte; the first non-ASCII value is 2^7.
private const val UTF8_ASCII_CEILING = 0x80

// why: code points below 2^11 fit in two UTF-8 bytes.
private const val UTF8_TWO_BYTE_CEILING = 0x800

// why: a non-surrogate Basic Multilingual Plane code point occupies three UTF-8 bytes.
private const val UTF8_BMP_WIDTH = 3

// why: a supplementary code point occupies four UTF-8 bytes rather than two UTF-16 code units.
private const val UTF8_SUPPLEMENTARY_WIDTH = 4

// Fixed scratch, independent of prompt length; enough for ordinary stream writes without whole-string copies.
internal const val WIRE_BUFFER_BYTES = 8_192

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

    public fun string(element: JsonElement): String = Json.encodeToString(WireElementSerializer, element)

    /** Writes with fixed scratch and leaves the caller's stream open. */
    public fun write(element: JsonElement, output: OutputStream) {
        val wire = BoundedWire(output)
        wire.tree(element)
        wire.drain()
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

    private class BoundedWire(private val output: OutputStream) {
        private var buffer = ByteBuffer.allocate(WIRE_BUFFER_BYTES)
        private val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        fun tree(element: JsonElement) {
            when (element) {
                JsonNull -> raw("null")
                is JsonObject -> {
                    byte('{')
                    element.entries.forEachIndexed { index, (key, value) ->
                        if (index > 0) byte(',')
                        quoted(key)
                        byte(':')
                        tree(value)
                    }
                    byte('}')
                }
                is JsonArray -> {
                    byte('[')
                    element.forEachIndexed { index, value ->
                        if (index > 0) byte(',')
                        tree(value)
                    }
                    byte(']')
                }
                is JsonPrimitive -> if (element.isString) quoted(element.content) else raw(element.content)
            }
        }

        private fun quoted(text: String) {
            byte('"')
            val borrowed = CharBuffer.wrap(text)
            var start = 0
            for (index in text.indices) {
                val escape = WIRE_ESCAPES.getOrNull(text[index].code)
                if (escape != null) {
                    span(borrowed, start, index)
                    escape.forEach(::byte)
                    start = index + 1
                }
            }
            span(borrowed, start, text.length)
            byte('"')
        }

        private fun raw(text: String) {
            span(CharBuffer.wrap(text), 0, text.length)
        }

        private fun span(borrowed: CharBuffer, start: Int, end: Int) {
            if (start == end) return
            val input = borrowed.limit(end).position(start)
            val encoding = encoder.reset()
            while (encoding.encode(input, buffer, true).isOverflow) drain()
            while (encoding.flush(buffer).isOverflow) drain()
        }

        private fun byte(character: Char) {
            if (!buffer.hasRemaining()) drain()
            buffer = buffer.put(character.code.toByte())
        }

        fun drain() {
            if (buffer.position() > 0) output.write(buffer.array(), 0, buffer.position())
            buffer = buffer.clear()
        }
    }

    /** Map/list serializers traverse the original nodes; raw literals avoid normalizing 1e2 into 100.0. */
    private object WireElementSerializer : KSerializer<JsonElement> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
        private val objects = MapSerializer(String.serializer(), this)
        private val arrays = ListSerializer(this)

        @OptIn(ExperimentalSerializationApi::class)
        override fun serialize(encoder: Encoder, value: JsonElement) {
            when (value) {
                JsonNull -> encoder.encodeNull()
                is JsonObject -> encoder.encodeSerializableValue(objects, value)
                is JsonArray -> encoder.encodeSerializableValue(arrays, value)
                is JsonPrimitive -> {
                    if (value.isString) {
                        encoder.encodeString(value.content)
                    } else {
                        encoder.encodeSerializableValue(JsonElement.serializer(), JsonUnquotedLiteral(value.content))
                    }
                }
            }
        }

        override fun deserialize(decoder: Decoder): JsonElement = JsonElement.serializer().deserialize(decoder)
    }
}
