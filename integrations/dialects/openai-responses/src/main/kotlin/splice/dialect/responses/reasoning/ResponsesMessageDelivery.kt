package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Reads native message text without copying a single existing text part. */
internal object ResponsesMessageText {
    fun parts(item: JsonObject): List<String> = when (val content = item["content"]) {
        is JsonArray -> content.mapNotNull { JsonScalars.str(it as? JsonObject, "text") }.filter { it.isNotEmpty() }
        else -> listOfNotNull(JsonScalars.str(content)).filter { it.isNotEmpty() }
    }

    fun text(item: JsonObject): String {
        val parts = parts(item)
        return if (parts.size == 1) parts.single() else parts.joinToString("")
    }
}

/** Completion can precede the final deltas. Their observed stream frontier remains separate from delivery. */
internal class ResponsesMessageDelivery {
    private val current = mutableMapOf<Int, MessageFrontier>()
    private val completed = mutableMapOf<String, MessageFrontier>()

    fun added(item: JsonObject, index: Int) {
        current.remove(index)
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE) {
            val id = JsonScalars.str(item, "id")
            current[index] = if (id == null) MessageFrontier(null) else completed[id] ?: MessageFrontier(id)
        }
    }

    fun delta(event: JsonObject, index: Int, text: String): String {
        val id = JsonScalars.str(event, "item_id")
        var frontier = current[index]
        if (id != null) frontier = completed[id] ?: frontier?.takeIf { it.id == id }
        if (frontier == null) frontier = MessageFrontier(id).also { current[index] = it }
        if (id == null && frontier.finished) frontier = MessageFrontier(null).also { current[index] = it }
        return frontier.observe(text)
    }

    fun completed(item: JsonObject, index: Int) {
        val parts = ResponsesMessageText.parts(item)
        if (parts.isEmpty()) {
            current.remove(index)
            return
        }
        val id = JsonScalars.str(item, "id")
        val frontier = current[index]?.takeIf { it.id == null || it.id == id } ?: MessageFrontier(id)
        frontier.complete(parts)
        current[index] = frontier
        if (id != null) completed.putIfAbsent(id, frontier)
    }
}

private class MessageFrontier(val id: String?) {
    private var observed = 0
    private var expected: List<String>? = null
    private var part = 0
    private var offset = 0
    val finished: Boolean get() = expected != null && part == expected?.size

    fun complete(parts: List<String>) {
        expected = parts
        part = 0
        offset = observed
        while (part < parts.size && offset >= parts[part].length) {
            offset -= parts[part++].length
        }
    }

    fun observe(text: String): String {
        observed += text.length
        val parts = expected ?: return text
        var at = 0
        while (at < text.length) {
            require(part < parts.size) { "upstream: text delta exceeds its completed message" }
            val source = parts[part]
            val count = minOf(source.length - offset, text.length - at)
            require(source.regionMatches(offset, text, at, count)) {
                "upstream: text delta disagrees with its completed message"
            }
            at += count
            offset += count
            if (offset == source.length) {
                part++
                offset = 0
            }
        }
        return ""
    }
}
