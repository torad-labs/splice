// NEW: per-block source extensions for the non-stream content fold.
package splice.head.wire

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.util.JsonScalars
import splice.upstream.transport.BufferCapacity

private const val CONTENT_BLOCK = "content_block"

/** Scoped source fields stay with the real opened block, never with a synthetic notice. */
internal class CollectedNativeFields {
    internal val native = NativeFields()
    private val blocks = mutableMapOf<Int, JsonObject>()
    private var retainedBytes = 0L

    internal fun hasBlock(index: WireBlockIndex): Boolean = blocks.containsKey(index.value)

    internal fun initial(field: String): String =
        JsonScalars.strOrEmpty((native.current(BLOCK_START)?.get(CONTENT_BLOCK) as? JsonObject)?.get(field))

    internal fun initialInput(): JsonObject? =
        (native.current(BLOCK_START)?.get(CONTENT_BLOCK) as? JsonObject)?.get("input") as? JsonObject

    internal fun start(index: Int) {
        val event = native.current(BLOCK_START)
        val payload = event?.get(CONTENT_BLOCK) as? JsonObject ?: JsonObject(emptyMap())
        val extensions = event?.let { native.extensions(it, "type", "index", CONTENT_BLOCK) }
        store(index, native.merge(payload, extensions ?: JsonObject(emptyMap())))
    }

    internal fun stop(index: WireBlockIndex) {
        val event = native.current("content_block_stop") ?: return
        keep(index, native.extensions(event, "type", "index"))
    }

    internal fun delta(index: WireBlockIndex) {
        val event = native.current("content_block_delta") ?: return
        keep(index, native.extensions(event, "type", "index", "delta"))
        val delta = event["delta"] as? JsonObject ?: return
        keep(index, native.extensions(delta, "type", "text", "thinking", "signature", "partial_json"))
    }

    internal fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        this.delta(index)
        keep(index, native.extensions(delta, "type"))
    }

    internal fun finish(index: Int, owned: JsonObject): JsonObject = native.merge(blocks[index], owned)

    internal fun raw(index: Int, initial: JsonObject): JsonObject = native.merge(initial, blocks[index] ?: initial)

    private fun store(index: Int, value: JsonObject) {
        val priorBytes = blocks[index]?.let(HeapJson::bytes) ?: 0L
        val nextBytes = retainedBytes - priorBytes + HeapJson.bytes(value)
        if (nextBytes >= BufferCapacity.MAX_BUFFERED_CHARS * 2L) throw HeapCapacityException()
        blocks[index] = value
        retainedBytes = nextBytes
    }

    private fun keep(index: WireBlockIndex, fields: JsonObject) {
        if (!blocks.containsKey(index.value)) return
        store(index.value, native.merge(blocks[index.value], fields))
    }
}
