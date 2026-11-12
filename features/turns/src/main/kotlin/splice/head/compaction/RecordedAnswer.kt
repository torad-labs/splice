// NEW (rig's finding, 2026-10-25): a streamed compaction's recording, collected into the ONE Messages body
// a non-stream retry reads. When Claude Code's streamed compaction dies it falls back to stream:false with
// the same request, and every dialect sends the same upstream bytes either way, so the fallback IS that
// compaction: it follows the detached drive's recording instead of starting a second read beside it
// (hack-program on glml53, 00:58 CT: a 65 ms later fallback prefilled 646k again and starved the first).
// The frames are this head's own (SseFrameWriter's `event: X\ndata: {json}\n\n`), so the fold reads a
// closed shape: the message from its start, each block from its start and deltas, and the stop fields and
// usage from the message delta. Nothing is derived here; every value is copied off a frame (L3).
package splice.head.compaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.head.wire.MESSAGE_DELTA

private const val DATA_LINE = "data:"
private const val PARTIAL_JSON = "partial_json"
private const val CITATION = "citation"
private const val CITATIONS = "citations"

internal class RecordedAnswer {
    private var start: JsonObject? = null
    private val blocks = ArrayList<MutableMap<String, JsonElement>>()
    private val toolInputs = HashMap<Int, StringBuilder>()
    private val stop = LinkedHashMap<String, JsonElement>()
    private val usage = LinkedHashMap<String, JsonElement>()

    /** One recorded frame. Only its data line carries the event; the event line and a keepalive are skipped. */
    fun accept(frame: String) {
        frame.lineSequence()
            .filter { it.startsWith(DATA_LINE) }
            .mapNotNull { JsonScalars.objectOrNull(Json, it.removePrefix(DATA_LINE).trim()) }
            .forEach(::event)
    }

    /** The collected message, or null when the recording never started one. */
    fun message(): JsonObject? {
        val message = start ?: return null
        val content = blocks.mapIndexed { index, block -> JsonObject(withInput(index, block)) }
        return JsonObject(message + stop + mapOf("content" to JsonArray(content), "usage" to JsonObject(usage)))
    }

    private fun event(event: JsonObject) {
        when (JsonScalars.str(event, "type")) {
            "message_start" -> started(event["message"] as? JsonObject)
            "content_block_start" -> opened(event)
            "content_block_delta" -> delta(event)
            MESSAGE_DELTA -> ended(event)
            // ping, content_block_stop and the message's stop carry nothing a collected body keeps.
            else -> Unit
        }
    }

    private fun started(message: JsonObject?) {
        start = message ?: return
        (message["usage"] as? JsonObject)?.let(usage::putAll)
    }

    private fun opened(event: JsonObject) {
        val index = JsonScalars.int(event, "index") ?: return
        val block = event["content_block"] as? JsonObject ?: return
        while (blocks.size <= index) blocks.add(LinkedHashMap())
        blocks[index] = LinkedHashMap(block)
    }

    // Each delta field extends the block field of the same name (text, thinking, signature); a tool's input
    // arrives as partial JSON, parsed whole at the end, and a citation joins the block's list.
    private fun delta(event: JsonObject) {
        val index = JsonScalars.int(event, "index") ?: return
        val block = blocks.getOrNull(index) ?: return
        val delta = event["delta"] as? JsonObject ?: return
        delta.forEach { (field, value) -> if (field != "type") extend(index, block, field, value) }
    }

    private fun extend(index: Int, block: MutableMap<String, JsonElement>, field: String, value: JsonElement) {
        when {
            field == PARTIAL_JSON -> toolInputs.getOrPut(index, ::StringBuilder).append(JsonScalars.strOrEmpty(value))
            field == CITATION -> block[CITATIONS] = JsonArray((block[CITATIONS] as? JsonArray).orEmpty() + value)
            value is JsonPrimitive && value.isString ->
                block[field] = JsonPrimitive(JsonScalars.strOrEmpty(block[field]) + JsonScalars.strOrEmpty(value))
            else -> block[field] = value
        }
    }

    private fun ended(event: JsonObject) {
        (event["delta"] as? JsonObject)?.forEach { (field, value) -> if (field != "type") stop[field] = value }
        (event["usage"] as? JsonObject)?.let(usage::putAll)
    }

    private fun withInput(index: Int, block: Map<String, JsonElement>): Map<String, JsonElement> {
        val input = toolInputs[index]?.takeIf { it.isNotEmpty() }?.let { JsonScalars.objectOrNull(Json, it.toString()) }
        return if (input == null) block else block + ("input" to input)
    }
}
