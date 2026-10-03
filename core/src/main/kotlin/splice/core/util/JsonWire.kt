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
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream

// why: ASCII code points fit in one UTF-8 byte; the first non-ASCII value is 2^7.
private const val UTF8_ASCII_CEILING = 0x80

// why: code points below 2^11 fit in two UTF-8 bytes.
private const val UTF8_TWO_BYTE_CEILING = 0x800

// why: a non-surrogate Basic Multilingual Plane code point occupies three UTF-8 bytes.
private const val UTF8_BMP_WIDTH = 3

// why: a supplementary code point occupies four UTF-8 bytes rather than two UTF-16 code units.
private const val UTF8_SUPPLEMENTARY_WIDTH = 4

/** Streams borrowed tree nodes while retaining the legacy wire's numeric lexemes and string escaping. */
public object JsonWire {
    public fun string(element: JsonElement): String = Json.encodeToString(WireElementSerializer, element)

    @OptIn(ExperimentalSerializationApi::class)
    public fun write(element: JsonElement, output: OutputStream) {
        Json.encodeToStream(WireElementSerializer, element, output)
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
