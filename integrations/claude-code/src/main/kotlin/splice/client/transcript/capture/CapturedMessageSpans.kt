package splice.client.transcript.capture

import kotlinx.serialization.json.Json
import splice.core.util.JsonScalars

// why: the eight-character messages key needs at most 50 quoted characters with Unicode escapes; 64 bounds scratch.
private const val MAX_CAPTURE_FIELD_CHARS = 64

internal data class CaptureSpan(val start: Int, val end: Int, val bytes: Long = 0L)

internal fun interface CapturedSpanVisit {
    fun span(span: CaptureSpan): Boolean
}

/** A full verification pass finds only the last messages array. Subsequent passes keep bounded source spans. */
internal class CapturedMessageSpans(private val body: CapturedRequestReader) {
    val array: CaptureSpan? = body.text().use(::arrayOfMessages)

    fun scan(visit: CapturedSpanVisit) {
        val array = array ?: return
        body.text().use { text ->
            text.seek(array.start + 1)
            val values = CapturedJsonValues(text)
            values.space()
            while (text.position < array.end - 1) {
                val start = text.position
                val bytes = text.bytes
                val end = values.end()
                if (!visit.span(CaptureSpan(start, end, text.bytes - bytes))) return
                values.space()
                if (text.peek() == ',') {
                    text.take()
                    values.space()
                }
            }
        }
    }

    /** Duplicate top-level fields retain last-value-wins, including a final non-array messages value. */
    private fun arrayOfMessages(text: CapturedText): CaptureSpan? {
        val values = CapturedJsonValues(text)
        values.space()
        require(text.take() == '{')
        values.space()
        var messages: CaptureSpan? = null
        while (text.peek() != '}') {
            text.mark(MAX_CAPTURE_FIELD_CHARS)
            values.stringEnd()
            val key = text.marked()?.let { JsonScalars.str(Json.parseToJsonElement(it)) }
            values.space()
            require(text.take() == ':')
            val start = values.space()
            val isArray = text.peek() == '['
            val end = values.end()
            if (key == "messages") messages = CaptureSpan(start, end).takeIf { isArray }
            values.space()
            if (text.peek() != '}') {
                require(text.take() == ',')
                values.space()
                require(text.peek() != '}')
            }
        }
        text.take()
        values.space()
        require(text.peek() == null)
        return messages
    }
}
