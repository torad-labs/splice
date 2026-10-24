package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.upstream.transport.BufferCapacity

private const val NATIVE_MESSAGE_TYPE = "message"

/** Completed native items retain their message boundaries independently of flattened display buffers. */
internal class ResponsesNativePrefix {
    private val items = mutableListOf<NativePrefixItem>()
    private val completedIds = hashSetOf<String>()
    private val pending = sortedMapOf<Int, StringBuilder>()
    private var sealed: List<JsonObject>? = null
    private var boundary: Int? = null
    var retainedChars: Long = 0
        private set

    fun delta(index: Int, text: String) {
        pending.getOrPut(index) { StringBuilder() }.append(text)
        retainedChars += text.length
        require(retainedChars <= BufferCapacity.MAX_BUFFERED_CHARS) { "native history exceeds buffer capacity" }
    }

    /** Keep complete upstream snapshots separately from the text the client actually received. */
    fun collect(item: JsonObject, index: Int) {
        if (!replayable(item)) return
        val isMessage = JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE
        val streamed = pending.remove(index)?.toString().orEmpty()
        retainedChars -= streamed.length
        val captured = when {
            !isMessage -> {
                // Reusing an index starts another item; the preceding unfinished message remains history.
                if (streamed.isNotEmpty()) retain(message(streamed), index)
                item
            }
            text(item).isEmpty() && streamed.isNotEmpty() ->
                JsonObject(item + ("content" to checkNotNull(content(message(streamed)))))
            else -> item
        }
        retain(captured, index)
    }

    private fun retain(item: JsonObject, index: Int) {
        val id = JsonScalars.str(item, "id")
        if (id != null && !completedIds.add(id)) return
        retainedChars += JsonWire.string(item).length
        require(retainedChars <= BufferCapacity.MAX_BUFFERED_CHARS) { "native history exceeds buffer capacity" }
        items.add(NativePrefixItem(index, item))
    }

    private fun text(item: JsonObject): String {
        val content = content(item)
        return if (content is JsonArray) {
            content.joinToString("") { JsonScalars.str(it as? JsonObject, "text").orEmpty() }
        } else {
            JsonScalars.strOrEmpty(content)
        }
    }

    fun seal(index: Int?) {
        if (boundary != null) return
        val lastCompleted = items.maxOfOrNull { it.index }
        val lastPending = pending.keys.lastOrNull()
        val last = listOfNotNull(lastCompleted, lastPending).maxOrNull()
        boundary = index ?: last?.plus(1) ?: 0
        sealed = snapshot()
    }

    /** A sparse terminal cannot remove or permute an item already captured before the call sealed. */
    fun finish(output: JsonArray) {
        val complete = output.mapNotNull { it as? JsonObject }
        val callAt = complete.indexOfFirst { JsonScalars.str(it, "type") == "custom_tool_call" }
        if (callAt < 0) return
        val terminal = complete.take(callAt).filter(::replayable)
        val before = sealed ?: snapshot()
        val known = before.associateBy(::identity)
        val anonymous = anonymousMatches(before, terminal)
        val matched = terminal.mapNotNull { known[identity(it)] ?: anonymous[it] }
        if (matched != before) return
        val reconciled = terminal.map { candidate -> known[identity(candidate)] ?: candidate }
        val added = terminal.filter { identity(it) !in known }
        retainedChars += added.sumOf { JsonWire.string(it).length.toLong() }
        require(retainedChars <= BufferCapacity.MAX_BUFFERED_CHARS) { "native history exceeds buffer capacity" }
        boundary = boundary ?: callAt
        sealed = reconciled
    }

    /** Exact text is an anchor only when unique on both sides; known anchors must retain their order. */
    private fun anonymousMatches(before: List<JsonObject>, terminal: List<JsonObject>): Map<JsonObject, JsonObject> {
        val anonymous = before.filter { JsonScalars.str(it, "id") == null }.groupBy(::messageKey)
        val candidates = terminal.groupBy(::messageKey)
        return anonymous.filterKeys { it != null }.filterValues { it.size == 1 }.mapNotNull { (key, entries) ->
            candidates[key]?.singleOrNull()?.let { it to entries.single() }
        }.toMap()
    }

    private fun messageKey(item: JsonObject): String? =
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE && JsonScalars.str(item, "role") == "assistant") {
            text(item).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    fun snapshot(): List<JsonObject> = sealed ?: buildList {
        val end = boundary ?: Int.MAX_VALUE
        val completed = items.filter { it.index < end }
        val unfinished = pending.filterKeys {
            it < end
        }.map { (index, text) -> NativePrefixItem(index, message(text.toString())) }
        addAll((completed + unfinished).sortedBy { it.index }.map { it.item })
    }

    private fun identity(item: JsonObject): JsonElement = item["id"] ?: item

    private fun content(item: JsonObject): JsonElement? = item["content"]

    private fun replayable(item: JsonObject): Boolean = when (JsonScalars.str(item, "type")) {
        NATIVE_MESSAGE_TYPE -> true
        "reasoning" -> !JsonScalars.str(item, "encrypted_content").isNullOrEmpty()
        else -> false
    }

    private fun message(text: String): JsonObject = JsonObject(
        ResponsesAssistantText.item(text, AssistantPhase.COMMENTARY) + ("type" to JsonPrimitive(NATIVE_MESSAGE_TYPE)),
    )
}

private data class NativePrefixItem(val index: Int, val item: JsonObject)
