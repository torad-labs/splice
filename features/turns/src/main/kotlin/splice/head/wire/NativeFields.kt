// NEW: opaque source fields survive typed wire delivery without leaking into synthetic frames.
package splice.head.wire

import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.util.JsonScalars
import splice.upstream.sse.SourceFrameAction
import splice.upstream.sse.WireSink
import splice.upstream.transport.BufferCapacity

private const val NATIVE_TYPE = "type"
private const val NATIVE_DELTA = "delta"

// The keys a content_block_delta writer owns at the frame's top level.
private val NATIVE_DELTA_FRAME_KEYS = setOf(NATIVE_TYPE, "index", NATIVE_DELTA)

/** One turn writer's temporary source frame. The progress writer owns a separate instance. */
internal class NativeFields {
    private var source: JsonObject? = null

    internal suspend fun deliver(event: JsonObject, sink: WireSink, action: SourceFrameAction) {
        val prior = source
        source = event
        try {
            action.deliver(sink)
        } finally {
            source = prior
        }
    }

    internal fun enrich(type: String, owned: JsonObject): JsonObject {
        val event = source ?: return owned
        return if (JsonScalars.strOrEmpty(event["type"]) == type) merge(event, owned) else owned
    }

    internal fun current(type: String): JsonObject? = source?.takeIf { has(type) }

    internal fun has(type: String): Boolean = JsonScalars.strOrEmpty(source?.get("type")) == type

    /** True when the source frame in scope holds nothing a delta writer would not write itself: only `type`, `index`
     *  and a `delta` of `type` and [field]. Merging such a frame changes no value, so the writer can build its frame
     *  by hand instead of through the tree, which is every token of a passthrough turn whose upstream adds nothing. */
    internal fun onlyProtocol(field: String): Boolean {
        val event = source ?: return true
        val delta = event[NATIVE_DELTA] as? JsonObject ?: return false
        return NATIVE_DELTA_FRAME_KEYS.containsAll(event.keys) && delta.keys.all { it == NATIVE_TYPE || it == field }
    }

    /** Owned protocol fields win. Objects merge recursively; opaque nulls and arrays stay intact. */
    internal fun merge(source: JsonObject?, owned: JsonObject): JsonObject {
        if (source == null) return bounded(owned)
        val fields = source.toMutableMap()
        for ((key, value) in owned) {
            val prior = fields[key]
            fields[key] = if (prior is JsonObject && value is JsonObject) merge(prior, value) else value
        }
        return bounded(JsonObject(fields))
    }

    /** Opaque fields obey the existing response capacity, including object and entry overhead. */
    private fun bounded(value: JsonObject): JsonObject {
        if (HeapJson.bytes(value) >= BufferCapacity.MAX_BUFFERED_CHARS * 2L) throw HeapCapacityException()
        return value
    }

    internal fun extensions(source: JsonObject, vararg protocol: String): JsonObject =
        JsonObject(source.filterKeys { it !in protocol })
}
