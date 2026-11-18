// NEW: the agent's call (docs/specs/harness.md, decision 12). The model's message as the provider streamed it, put
// together from its own frames the way a client puts it together: each block as it started, its deltas applied, a
// tool call's input parsed. Its blocks go back to the provider byte for byte (thinking and its signature included),
// and the ones the client was shown are the client's copy, which a later request is matched by.
package splice.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.parse.lenientJson
import splice.core.util.JsonScalars

/** One message being put together. A tool call named in [hidden] is the capability's: the client is never shown it. */
internal class MessageAssembly(private val hidden: Set<String>) {
    private val blocks = sortedMapOf<Int, Building>()

    /** Takes one frame of the provider's stream. */
    fun accept(event: JsonObject) {
        val index = JsonScalars.int(event, INDEX) ?: return
        when (JsonScalars.str(event, TYPE)) {
            "content_block_start" ->
                (event["content_block"] as? JsonObject)?.let { blocks[index] = Building(it, hidden) }
            "content_block_delta" -> (event["delta"] as? JsonObject)?.let { blocks[index]?.delta(it) }
        }
    }

    /** The message's blocks, in the order the provider numbered them. */
    fun blocks(): List<JsonObject> = blocks.values.map(Building::block)

    /** The blocks the client was shown: every one but the capability's calls. */
    fun shown(): List<JsonObject> = blocks.values.filterNot(Building::hidden).map(Building::block)

    /** One block: what its start said, and what its deltas added to each field. */
    private class Building(private val start: JsonObject, hidden: Set<String>) {
        val hidden: Boolean = JsonScalars.str(start, TYPE) == "tool_use" && JsonScalars.str(start, "name") in hidden
        private val added = LinkedHashMap<String, StringBuilder>()
        private val partialJson = StringBuilder()
        private val citations = mutableListOf<JsonElement>()

        fun delta(delta: JsonObject) {
            when (JsonScalars.str(delta, TYPE)) {
                "text_delta" -> append("text", delta)
                "thinking_delta" -> append("thinking", delta)
                "signature_delta" -> append("signature", delta)
                "input_json_delta" -> partialJson.append(JsonScalars.strOrEmpty(delta["partial_json"]))
                "citations_delta" -> delta["citation"]?.let(citations::add)
            }
        }

        private fun append(field: String, delta: JsonObject) {
            added.getOrPut(field) { StringBuilder(JsonScalars.strOrEmpty(start[field])) }
                .append(JsonScalars.strOrEmpty(delta[field]))
        }

        /** The block as the client assembles it. A call's input that does not read as an object keeps what its start
         *  said, as a client that cannot parse it keeps it. */
        fun block(): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>(start)
            added.forEach { (field, text) -> fields[field] = JsonPrimitive(text.toString()) }
            if (partialJson.isNotEmpty()) {
                JsonScalars.objectOrNull(lenientJson, partialJson.toString())?.let { fields[INPUT] = it }
            }
            if (citations.isNotEmpty()) {
                fields[CITATIONS] = JsonArray((start[CITATIONS] as? JsonArray).orEmpty() + citations)
            }
            return JsonObject(fields)
        }
    }
}

private const val INDEX = "index"
private const val TYPE = "type"
private const val INPUT = "input"
private const val CITATIONS = "citations"
