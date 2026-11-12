package splice.head.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

// Ordinary token deltas keep their wire bytes; only a large visible update needs subdivision.
private const val SPLIT_DELTA_CHARS = 256

// why: four 32-character slices per 16 ms tick spread a small batch at about 8,000 characters a second.
private const val SLICE_CHARS = 32

// why: a very large provider frame must not expand into an unbounded number of protocol frames.
private const val MAX_DELTA_SLICES = 256
private const val VISIBLE_DELTA_EVENT = "event: content_block_delta\n"

/** Keeps one original payload and renders its next slice only when the pacer releases it. */
internal class VisibleDeltaSlices(
    private val payload: JsonObject,
    private val delta: JsonObject,
    private val field: String,
    private val value: String,
) {
    private var offset = 0
    private val sliceChars = maxOf(SLICE_CHARS, (value.length + MAX_DELTA_SLICES - 1) / MAX_DELTA_SLICES)

    /** Upper bound on remaining slices; a surrogate pair may make a slice one UTF-16 unit wider. */
    val remaining: Int get() = (value.length - offset + sliceChars - 1) / sliceChars

    fun next(): String {
        var end = minOf(value.length, offset + sliceChars)
        if (end < value.length && Character.isSurrogatePair(value[end - 1], value[end])) end++
        return frame(end).also { offset = end }
    }

    /** A stopped pacer drains just its unread suffix, so already delivered content cannot repeat. */
    fun remainder(): String = frame(value.length)

    private fun frame(end: Int): String {
        val part = JsonObject(delta + (field to JsonPrimitive(value.substring(offset, end))))
        val data = JsonWire.string(JsonObject(payload + ("delta" to part)))
        return "${VISIBLE_DELTA_EVENT}data: $data\n\n"
    }
}

internal object VisibleDeltaSplitter {
    fun split(frame: String): VisibleDeltaSlices? {
        if (frame.length <= SPLIT_DELTA_CHARS || !frame.startsWith(VISIBLE_DELTA_EVENT)) return null
        val payload = JsonScalars.objectOrNull(Json, frame.substringAfter("data:", "")) ?: return null
        return slices(payload)
    }

    private fun slices(payload: JsonObject): VisibleDeltaSlices? {
        val delta = payload["delta"] as? JsonObject ?: return null
        val field = when (JsonScalars.str(delta, "type")) {
            "text_delta" -> "text"
            "thinking_delta" -> "thinking"
            else -> null
        }
        return field?.let { key ->
            JsonScalars.str(delta, key)?.takeIf { it.length > SPLIT_DELTA_CHARS }?.let { value ->
                VisibleDeltaSlices(payload, delta, key, value)
            }
        }
    }
}
