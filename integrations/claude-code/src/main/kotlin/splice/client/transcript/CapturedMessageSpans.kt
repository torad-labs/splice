package splice.client.transcript

import kotlinx.serialization.json.Json
import splice.core.util.JsonScalars

private const val MAX_CAPTURE_FIELD_CHARS = 64
private const val UTF8_TWO_BYTES = 2
private const val UTF8_THREE_BYTES = 3
private const val UTF8_FOUR_BYTES = 4
private const val ASCII_END = 0x7f
private const val UTF8_TWO_END = 0x7ff

internal data class CaptureSpan(val start: Int, val end: Int)

/** Only source offsets survive scanning. No parsed message or content copy is retained by this index. */
internal class CapturedMessageSpans(private val body: String) {
    private val values = CapturedJsonValues(body)
    val array: CaptureSpan? = arrayOfMessages()

    fun all(): Sequence<CaptureSpan> = sequence {
        val array = array ?: return@sequence
        var at = values.space(array.start + 1)
        while (at < array.end - 1) {
            val end = values.end(at)
            yield(CaptureSpan(at, end))
            at = values.space(end)
            if (body.getOrNull(at) == ',') at = values.space(at + 1)
        }
    }

    fun bytes(span: CaptureSpan): Long {
        var bytes = 0L
        var at = span.start
        while (at < span.end) {
            val char = body[at++]
            bytes += when {
                char.code <= ASCII_END -> 1
                char.code <= UTF8_TWO_END -> UTF8_TWO_BYTES
                char.isHighSurrogate() && at < span.end && body[at].isLowSurrogate() -> {
                    at++
                    UTF8_FOUR_BYTES
                }
                char.isSurrogate() -> 1 // The JVM's UTF-8 replacement for an unmatched surrogate.
                else -> UTF8_THREE_BYTES
            }
        }
        return bytes
    }

    /** Duplicate top-level fields follow JsonObject's last-value-wins rule. Unknown fields are validated and skipped. */
    private fun arrayOfMessages(): CaptureSpan? {
        var at = values.space(0)
        require(body.getOrNull(at) == '{')
        at = values.space(at + 1)
        var messages: CaptureSpan? = null
        while (body.getOrNull(at) != '}') {
            val keyEnd = values.stringEnd(at)
            val key = if (keyEnd - at <= MAX_CAPTURE_FIELD_CHARS) {
                JsonScalars.str(Json.parseToJsonElement(body.substring(at, keyEnd)))
            } else {
                null
            }
            at = values.space(keyEnd)
            require(body.getOrNull(at) == ':')
            val start = values.space(at + 1)
            val end = values.end(start)
            if (key == "messages") messages = CaptureSpan(start, end).takeIf { body[start] == '[' }
            at = values.space(end)
            if (body.getOrNull(at) != '}') {
                require(body.getOrNull(at) == ',')
                at = values.space(at + 1)
                require(body.getOrNull(at) != '}')
            }
        }
        require(values.space(at + 1) == body.length)
        return messages
    }
}
