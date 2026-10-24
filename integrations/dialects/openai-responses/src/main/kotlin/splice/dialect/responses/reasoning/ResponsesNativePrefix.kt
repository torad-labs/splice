package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.upstream.transport.BufferCapacity

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

    fun collect(item: JsonObject, index: Int): String? {
        val isMessage = JsonScalars.str(item, "type") == "message"
        if (!replayable(item)) return null
        val streamed = pending.remove(index)?.toString().orEmpty()
        retainedChars -= streamed.length
        val missingMessageText = isMessage && text(item).isEmpty()
        val captured = if (missingMessageText && streamed.isNotEmpty()) {
            JsonObject(item + ("content" to checkNotNull(content(message(streamed)))))
        } else {
            item
        }
        val id = JsonScalars.str(captured, "id")
        if (id == null || completedIds.add(id)) {
            retainedChars += captured.toString().length
            require(retainedChars <= BufferCapacity.MAX_BUFFERED_CHARS) { "native history exceeds buffer capacity" }
            items.add(NativePrefixItem(index, captured))
        }
        return text(captured).takeIf { isMessage }
    }

    private fun text(item: JsonObject): String {
        val content = content(item)
        return if (content is JsonArray) {
            content.joinToString("") { JsonScalars.str(it as? JsonObject, "text").orEmpty() }
        } else {
            JsonScalars.strOrEmpty(content)
        }
    }

    fun seal(index: Int?, fallback: Int) {
        if (boundary != null) return
        boundary = index ?: fallback
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
        val matched = terminal.mapNotNull { known[identity(it)] }
        if (matched != before) return
        val reconciled = terminal.map { candidate -> known[identity(candidate)] ?: candidate }
        val added = terminal.filter { identity(it) !in known }
        retainedChars += added.sumOf { it.toString().length.toLong() }
        require(retainedChars <= BufferCapacity.MAX_BUFFERED_CHARS) { "native history exceeds buffer capacity" }
        boundary = boundary ?: callAt
        sealed = reconciled
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
        "message" -> true
        "reasoning" -> !JsonScalars.str(item, "encrypted_content").isNullOrEmpty()
        else -> false
    }

    private fun message(text: String): JsonObject = JsonObject(
        ResponsesAssistantText.item(text, AssistantPhase.COMMENTARY) + ("type" to JsonPrimitive("message")),
    )
}

private data class NativePrefixItem(val index: Int, val item: JsonObject)
