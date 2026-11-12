package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Completion can precede the final deltas. Their observed stream frontier remains separate from delivery. */
internal class ResponsesMessageDelivery {
    private val current = mutableMapOf<Int, MessageFrontier>()
    private val identified = mutableMapOf<String, IndexedValue<MessageFrontier>>()

    /** Explicit source indexes win; an existing item identity precedes any synthetic fallback. */
    fun index(id: String?, offered: Int?): Int? = offered ?: id?.let { identified[it]?.index }

    fun added(item: JsonObject, index: Int) {
        current.remove(index)
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE) {
            val id = JsonScalars.str(item, "id")
            val frontier = id?.let { identified[it]?.value } ?: MessageFrontier(id)
            current[index] = frontier
            if (id != null) identified.putIfAbsent(id, IndexedValue(index, frontier))
        }
    }

    fun delta(event: JsonObject, index: Int, text: String): String {
        val id = JsonScalars.str(event, "item_id")
        var frontier = messageFrontier(event, index)
        if (id == null && frontier.finished) frontier = MessageFrontier(null).also { current[index] = it }
        val contentIndex = JsonScalars.str(event, "content_index")?.toIntOrNull() ?: 0
        return frontier.observe(contentIndex, text)
    }

    fun completedPart(event: JsonObject, index: Int, contentIndex: Int, text: String): String =
        messageFrontier(event, index).completePart(contentIndex, text)

    private fun messageFrontier(event: JsonObject, index: Int): MessageFrontier {
        val id = JsonScalars.str(event, "item_id")
        var frontier = current[index]
        if (id != null) frontier = identified[id]?.value ?: frontier?.takeIf { it.id == id }
        return frontier ?: MessageFrontier(id).also {
            current[index] = it
            if (id != null) identified[id] = IndexedValue(index, it)
        }
    }

    fun completed(item: JsonObject, index: Int) {
        val parts = indexedParts(item)
        if (parts.isEmpty()) {
            current.remove(index)
            return
        }
        val id = JsonScalars.str(item, "id")
        val frontier = current[index]?.takeIf { it.id == null || it.id == id } ?: MessageFrontier(id)
        frontier.complete(parts)
        current[index] = frontier
        if (id != null) identified.putIfAbsent(id, IndexedValue(index, frontier))
    }

    private fun indexedParts(item: JsonObject): List<Pair<Int, String>> {
        val content = item["content"] as? JsonArray
        return content?.mapIndexedNotNull { index, part ->
            JsonScalars.str(part as? JsonObject, "text")
                ?.takeIf { it.isNotEmpty() }
                ?.let { index to it }
        } ?: ResponsesMessageText.parts(item).mapIndexed { index, text -> index to text }
    }
}

private class MessageFrontier(val id: String?) {
    private val parts = mutableMapOf<Int, TextPartFrontier>()
    private var completed = false
    val finished: Boolean get() = completed && parts.values.all { it.finished }

    fun complete(parts: List<Pair<Int, String>>) {
        parts.forEach { (index, text) -> this.parts.getOrPut(index, ::TextPartFrontier).seal(text) }
        completed = true
    }

    fun completePart(index: Int, text: String): String =
        parts.getOrPut(index, ::TextPartFrontier).complete(text)

    fun observe(index: Int, text: String): String =
        parts.getOrPut(index, ::TextPartFrontier).observe(text)

    private class TextPartFrontier {
        private var deliveredLength = 0
        private var expected: String? = null
        private var offset = 0
        val finished: Boolean get() = expected?.length == offset

        fun complete(text: String): String {
            require(text.length >= deliveredLength) { "upstream: text done is shorter than streamed deltas" }
            val suffix = text.substring(deliveredLength)
            seal(text)
            return suffix
        }

        fun seal(text: String) {
            require(text.length >= offset) { "upstream: completed message is shorter than streamed deltas" }
            expected = text
            deliveredLength = text.length
        }

        fun observe(text: String): String {
            val complete = expected ?: run {
                offset += text.length
                deliveredLength = offset
                return text
            }
            if (offset == complete.length) offset = 0
            require(complete.regionMatches(offset, text, 0, text.length)) {
                "upstream: text delta disagrees with its completed message"
            }
            offset += text.length
            return ""
        }
    }
}
