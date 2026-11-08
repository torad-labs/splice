package splice.app.peer.codex

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

// why: desktop stream snapshots can be large, but only the small routing/status projection is retained.
internal const val CODEX_IPC_MAX_INBOUND_FRAME_BYTES = 268_435_456L

internal const val IPC_MAX_RESPONSE_RESULT_BYTES = 1_048_576
private const val IPC_MAX_PROJECTED_BYTES = 2_097_152
private const val IPC_MAX_RETAINED_STRING_CHARS = 1_024
internal const val IPC_MAX_NESTING = 64
private const val IPC_END_OF_STREAM = -1
private const val IPC_UNSIGNED_BYTE_MASK = 0xFF
private val IPC_ROUTING_FIELDS =
    setOf("requestId", "method", "sourceClientId", "resultType", "error", "handledByClientId")

private val IPC_JSON_FACTORY: JsonFactory = JsonFactoryBuilder()
    .disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES)
    .disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
    .streamReadConstraints(
        StreamReadConstraints.builder()
            .maxStringLength(IPC_MAX_RETAINED_STRING_CHARS)
            .maxNameLength(IPC_MAX_RETAINED_STRING_CHARS)
            .maxNestingDepth(IPC_MAX_NESTING)
            .maxNumberLength(IPC_MAX_RETAINED_STRING_CHARS)
            .build(),
    )
    .build()

/** Streams an IPC frame into bounded JSON containing only routing and thread-status facts. */
internal object CodexIpcProjection {
    fun read(channel: SocketChannel, frameBytes: Long): String {
        val input = BoundedChannelInputStream(channel, frameBytes)
        val output = LimitedByteArrayOutputStream(IPC_MAX_PROJECTED_BYTES)
        val context = ProjectionContext(frameBytes)
        IPC_JSON_FACTORY.createParser(input).use { parser ->
            projectRoot(parser, output, context)
        }
        if (input.remaining != 0L) invalid("the desktop IPC frame length did not match its JSON")
        if (context.messageType == "response" && frameBytes > IPC_MAX_RESPONSE_RESULT_BYTES) {
            invalid("the desktop IPC response exceeded its bounded result size")
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    internal fun scalarText(parser: JsonParser): String? =
        if (parser.currentToken() == JsonToken.VALUE_STRING) parser.text else null

    internal fun skipValue(parser: JsonParser, token: JsonToken) {
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) parser.skipChildren()
    }

    internal fun invalid(reason: String): Nothing = throw IOException(reason)

    private fun projectRoot(parser: JsonParser, output: LimitedByteArrayOutputStream, context: ProjectionContext) {
        if (parser.nextToken() != JsonToken.START_OBJECT) invalid("a desktop IPC frame was not an object")
        IPC_JSON_FACTORY.createGenerator(output).use { generator ->
            generator.writeStartObject()
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                val name = parser.currentName() ?: invalid("a desktop IPC field had no name")
                val token = parser.nextToken() ?: invalid("a desktop IPC field had no value")
                projectField(parser, generator, name, token, context)
            }
            if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                invalid("a desktop IPC frame had trailing or incomplete JSON")
            }
            generator.writeEndObject()
        }
    }

    private fun projectField(
        parser: JsonParser,
        generator: JsonGenerator,
        name: String,
        token: JsonToken,
        context: ProjectionContext,
    ) {
        when (name) {
            "type" -> readType(parser, generator, token, context)
            "version" -> writeSmallScalar(parser, generator, name, token)
            "targetClientIds" -> writeStringArray(parser, generator, name, token)
            "params" -> CodexIpcStatusProjection.projectParams(parser, generator, token)
            "result" -> CodexIpcResponseProjection.writeResult(parser, generator, token, context.frameBytes)
            else -> writeRoutingField(parser, generator, name, token)
        }
    }

    private fun readType(parser: JsonParser, generator: JsonGenerator, token: JsonToken, context: ProjectionContext) {
        val type = scalarText(parser)
        if (type == null) {
            skipValue(parser, token)
        } else {
            context.messageType = type
            generator.writeStringField("type", type)
        }
    }

    private fun writeRoutingField(parser: JsonParser, generator: JsonGenerator, name: String, token: JsonToken) {
        if (name in IPC_ROUTING_FIELDS) {
            scalarText(parser)?.let { generator.writeStringField(name, it) } ?: skipValue(parser, token)
        } else {
            skipValue(parser, token)
        }
    }

    internal fun writeSmallScalar(parser: JsonParser, generator: JsonGenerator, name: String, token: JsonToken) {
        when (token) {
            JsonToken.VALUE_STRING -> writeTextScalar(parser, generator, name, token)
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> writeNumberScalar(
                parser,
                generator,
                name,
                token,
            )
            JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE -> generator.writeBooleanField(
                name,
                token == JsonToken.VALUE_TRUE,
            )
            JsonToken.VALUE_NULL -> generator.writeNullField(name)
            else -> skipValue(parser, token)
        }
    }

    private fun writeTextScalar(parser: JsonParser, generator: JsonGenerator, name: String, token: JsonToken) {
        scalarText(parser)?.let { generator.writeStringField(name, it) } ?: skipValue(parser, token)
    }

    private fun writeNumberScalar(parser: JsonParser, generator: JsonGenerator, name: String, token: JsonToken) {
        val number = parser.text
        if (token == JsonToken.VALUE_NUMBER_INT) {
            number.toLongOrNull()?.let { generator.writeNumberField(name, it) }
        } else {
            number.toDoubleOrNull()?.let { generator.writeNumberField(name, it) }
        }
    }

    private fun writeStringArray(parser: JsonParser, generator: JsonGenerator, name: String, token: JsonToken) {
        if (token != JsonToken.START_ARRAY) {
            skipValue(parser, token)
            return
        }
        generator.writeArrayFieldStart(name)
        while (true) {
            val item = parser.nextToken() ?: invalid("desktop IPC target list was incomplete")
            if (item == JsonToken.END_ARRAY) break
            scalarText(parser)?.let(generator::writeString) ?: skipValue(parser, item)
        }
        generator.writeEndArray()
    }
}

/** Reads at most one frame directly from the channel; it never wraps the socket in a shared Channels lock. */
private class BoundedChannelInputStream(
    private val channel: SocketChannel,
    frameBytes: Long,
) : InputStream() {
    var remaining: Long = frameBytes
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) IPC_END_OF_STREAM else one[0].toInt() and IPC_UNSIGNED_BYTE_MASK
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = minOf(length.toLong(), remaining).toInt()
        val read = channel.read(ByteBuffer.wrap(bytes, offset, count))
        if (read < 0) CodexIpcProjection.invalid("the desktop IPC frame ended before its declared length")
        if (read > 0) remaining -= read.toLong()
        return read
    }
}

private class LimitedByteArrayOutputStream(private val limit: Int) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        ensureRoom(1)
        super.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        ensureRoom(length)
        super.write(buffer, offset, length)
    }

    private fun ensureRoom(additional: Int) {
        if (additional > limit - size()) {
            CodexIpcProjection.invalid(
                "the projected desktop IPC message exceeded its limit",
            )
        }
    }
}

private data class ProjectionContext(val frameBytes: Long, var messageType: String? = null)
