// NEW: per-block source extensions for the non-stream content fold.
package splice.head.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.util.JsonScalars
import splice.upstream.transport.BufferCapacity

private const val CONTENT_BLOCK = "content_block"

/** A citations_delta's one citation, which [CollectedNativeFields.rawDelta] appends to the block's `citations`: a
 *  protocol key of that delta, never an opaque field merged onto the block. */
private const val CITATION_FIELD = "citation"
private const val BLOCK_CITATIONS = "citations"
private const val CITATION_DELTA_TYPE = "citations_delta"

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
        keep(index, native.extensions(delta, "type", "text", "thinking", "signature", "partial_json", CITATION_FIELD))
    }

    /** A delta object forwarded verbatim. A citations_delta adds its citation to the block's `citations` array, the
     *  shape the Messages body carries, one entry per delta; any other shape keeps its opaque fields on the block. */
    internal fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        this.delta(index)
        val citation = delta[CITATION_FIELD]?.takeIf { JsonScalars.strOrEmpty(delta["type"]) == CITATION_DELTA_TYPE }
        if (citation == null) keep(index, native.extensions(delta, "type")) else cite(index, citation)
    }

    private fun cite(index: WireBlockIndex, citation: JsonElement) {
        val block = blocks[index.value] ?: return
        val cited = (block[BLOCK_CITATIONS] as? JsonArray).orEmpty() + citation
        store(index.value, JsonObject(block + (BLOCK_CITATIONS to JsonArray(cited))))
    }

    internal fun finish(index: Int, owned: JsonObject): JsonObject = native.merge(blocks[index], owned)

    /** A raw block as it opened, with its streamed [args] (a server_tool_use's input_json_delta) folded into `input`
     *  when they parse. A server tool's input only describes a call the server already ran, so input that never
     *  parsed leaves the opened one in place rather than failing the turn. */
    internal fun raw(index: Int, initial: JsonObject, args: CharSequence = ""): JsonObject {
        val block = native.merge(initial, blocks[index] ?: initial)
        val input = args.takeIf { it.isNotBlank() }?.let { JsonScalars.objectOrNull(Json, it.toString()) }
        return if (input == null) block else JsonObject(block + ("input" to input))
    }

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
