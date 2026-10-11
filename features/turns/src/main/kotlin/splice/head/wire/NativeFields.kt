// NEW: opaque source fields survive typed wire delivery without leaking into synthetic frames.
package splice.head.wire

import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.util.JsonScalars
import splice.upstream.sse.SourceFrameAction
import splice.upstream.sse.WireSink
import splice.upstream.transport.BufferCapacity

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
