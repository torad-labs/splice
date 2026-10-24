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
internal const val UTF8_TWO_BYTE_CEILING = 0x800

// why: a non-surrogate Basic Multilingual Plane code point occupies three UTF-8 bytes.
internal const val UTF8_BMP_WIDTH = 3

// why: a supplementary code point occupies four UTF-8 bytes rather than two UTF-16 code units.
internal const val UTF8_SUPPLEMENTARY_WIDTH = 4

// why: one SSE frame or a small object fits in 256 characters; a longer text grows the scratch toward one chunk.
private const val STRING_WIRE_FIRST = 256

// The pinned library owns escaping. Borrow its exact ASCII escape spellings, never a second escape algorithm.
private val WIRE_ESCAPES = Array(UTF8_ASCII_CEILING) { code ->
    val character = code.toChar().toString()
    val quoted = Json.encodeToString(String.serializer(), character)
    quoted.substring(1, quoted.length - 1).takeUnless { it == character }
}

// why: the same spellings as the UTF-8 bytes a span of them wrote, so the one-pass stream copies them as is.
private val WIRE_ESCAPE_BYTES = Array(UTF8_ASCII_CEILING) { code -> WIRE_ESCAPES[code]?.toByteArray(Charsets.UTF_8) }

/** Streams borrowed tree nodes while retaining the legacy wire's numeric lexemes and string escaping. */
public object JsonWire {
    // Committed request shapes reach 24 container levels in the Anthropic goldens/request-mfjs-schema.json
    // and 9 in Responses contract/responses-codex-profile.json. 128 leaves >5x schema headroom while
    // bounding every recursive downstream walk. These are fixture measurements, not live-client maxima.
    public const val MAX_REQUEST_DEPTH: Int = 128

    // why: the nesting scan copies the body 8 Ki characters at a time, the stream wire's own chunk.
    private const val NESTING_CHUNK_CHARS = 8_192

    /** Refuses excessive container nesting before parsing; strings and their escapes are not structure.
     *  why: reading a 1-2 MB body through String.charAt paid a bounds check per character, the daemon's top leaf
     *  method, so the scan reads bulk copies. Its state is per character, so a chunk edge changes no decision. */
    public fun requireRequestNesting(text: String) {
        val scan = NestingScan()
        val chunk = CharArray(minOf(text.length, NESTING_CHUNK_CHARS))
        var offset = 0
        while (offset < text.length) {
            val count = minOf(chunk.size, text.length - offset)
            text.toCharArray(chunk, 0, offset, offset + count)
            scan.read(chunk, count)
            offset += count
        }
    }

    /** Container depth outside strings, carried from one chunk to the next. An escape is only ever pending inside
     *  a string, so whether a string is open decides how each character reads: structure or string text. */
    private class NestingScan {
        private var depth = 0
        private var quoted = false
        private var escaped = false

        fun read(chars: CharArray, count: Int) {
            var index = 0
            while (index < count) {
                index = if (quoted) insideString(chars, index, count) else outsideString(chars, index, count)
            }
        }

        /** Returns the index after the string's closing quote, or [count] while the string goes on. */
        private fun insideString(chars: CharArray, from: Int, count: Int): Int {
            var index = from
            var escape = escaped
            var open = true
            while (open && index < count) {
                val character = chars[index++]
                when {
                    escape -> escape = false
                    character == '\\' -> escape = true
                    character == '"' -> open = false
                }
            }
            escaped = escape
            quoted = open
            return index
        }

        /** Returns the index after a string's opening quote, or [count] while structure goes on. */
        private fun outsideString(chars: CharArray, from: Int, count: Int): Int {
            var index = from
            var level = depth
            var opened = false
            while (!opened && index < count) {
                when (chars[index++]) {
                    '"' -> opened = true
                    '{', '[' -> {
                        level++
                        require(level <= MAX_REQUEST_DEPTH) {
                            "request JSON nesting exceeds $MAX_REQUEST_DEPTH levels"
                        }
                    }
                    '}', ']' -> level--
                }
            }
            depth = level
            quoted = opened
            return index
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

    /** Counts the same escaped spans as the writer, without encoding them into UTF-8 scratch. */
    public fun byteSize(element: JsonElement): Long {
        val counter = SizeWire()
        WireTree(counter).tree(element)
        return counter.bytes
    }

    /** UTF-8 wire length, including the JVM encoder's single-byte replacement for an unpaired surrogate. */
    public fun byteSize(text: String): Long = byteSize(text, 0, text.length)

    private fun byteSize(text: String, start: Int, end: Int): Long {
        var bytes = 0L
        var offset = start
        while (offset < end) {
            val point = spanCodePoint(text, offset, end)
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

    /** A pair outside this borrowed span is replaced by the writer, not joined to its next span. */
    private fun spanCodePoint(text: String, offset: Int, end: Int): Int {
        val point = Character.codePointAt(text, offset)
        return if (offset + Character.charCount(point) <= end) point else text[offset].code
    }

    private class SizeWire : WireText {
        var bytes = 0L
            private set

        override fun raw(text: String, start: Int, end: Int) {
            bytes += byteSize(text, start, end)
        }

        override fun ascii(character: Char) {
            bytes += 1
        }
    }

    private interface WireText {
        fun raw(text: String, start: Int = 0, end: Int = text.length)
        fun ascii(character: Char)

        /** One JSON string: each escaped character's spelling in its place, the spans between written raw. */
        fun quoted(text: String) {
            ascii('"')
            var start = 0
            for (index in text.indices) {
                val escape = WIRE_ESCAPES.getOrNull(text[index].code)
                if (escape != null) {
                    raw(text, start, index)
                    raw(escape)
                    start = index + 1
                }
            }
            raw(text, start, text.length)
            ascii('"')
        }
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
                        output.quoted(key)
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
                is JsonPrimitive ->
                    if (element.isString) output.quoted(element.content) else output.raw(element.content)
            }
        }
    }

    /** Borrow large spans; the JDK join allocates the final string with its exact size and character width. */
    private class StringWire : WireText {
        private val parts = StringJoiner("")
        private var joined = false

        // Batches punctuation and small scalar spans up to the streaming scratch bound. It starts small and grows: most
        // calls are one SSE frame of a few hundred bytes, and a full-size start cost every frame 8 KiB (68 of 638 MB/s
        // sampled on the live daemon, Oct 20).
        private val chunk = StringBuilder(STRING_WIRE_FIRST)

        override fun raw(text: String, start: Int, end: Int) {
            val length = end - start
            if (length >= WIRE_BUFFER_BYTES) {
                flush()
                parts.add(text.substring(start, end))
                joined = true
            } else {
                if (chunk.length + length > WIRE_BUFFER_BYTES) flush()
                chunk.append(text, start, end)
            }
        }

        override fun ascii(character: Char) {
            if (chunk.length == WIRE_BUFFER_BYTES) flush()
            chunk.append(character)
        }

        /** A text that never filled the scratch is the scratch itself, with no join. */
        fun finish(): String {
            if (!joined) return chunk.toString()
            flush()
            return parts.toString()
        }

        private fun flush() {
            if (chunk.isNotEmpty()) {
                parts.add(chunk.toString())
                joined = true
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

        /** The bytes the split spans wrote, in one pass over bulk-copied characters.
         *  why: splitting read every character twice through String.charAt, which was most of each digest's CPU.
         *  An escaped character is ASCII and never a surrogate, so pairing over the whole string pairs as the
         *  spans did: a surrogate beside an escape was unpaired in its span and is unpaired here. */
        override fun quoted(text: String) {
            utf8.ascii('"')
            utf8.span(text, 0, text.length, WIRE_ESCAPE_BYTES)
            utf8.ascii('"')
        }

        fun drain() = utf8.drain()
    }
}
