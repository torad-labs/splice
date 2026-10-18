package splice.head.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

private const val DOCUMENT_PAGE_ENTRIES = 25
private const val DOCUMENT_TEXT_CHARS = 16_384
private const val DOCUMENT_PREVIEW_CHARS = 160
private const val DOCUMENT_KEY_CHARS = 4_096

/** A page contains direct-child previews or decoded text, never a complete nested subtree. */
internal class TraceDocumentNode(private val input: TraceDocumentText) {
    fun read(path: List<String>, offset: Int): JsonObject {
        path.forEach { seek(it) }
        input.space()
        return when (input.peek()) {
            '{', '[' -> children(offset)
            '"' -> text(offset, quoted = true)
            't', 'f', 'n', '-', in '0'..'9' -> primitive()
            else -> if (path.isEmpty()) text(offset, quoted = false) else throw IOException("not a document node")
        }
    }

    private fun seek(key: String) {
        input.space()
        val array = container()
        var index = 0
        input.space()
        while (input.peek() != if (array) ']' else '}') {
            val name = if (array) (index++).toString() else key()
            if (name == key) return
            input.skip()
            input.space()
            if (input.peek() != ',') break
            input.take()
        }
        throw IOException("document path was not found")
    }

    private fun container(): Boolean = when (input.take()) {
        '[' -> true
        '{' -> false
        else -> throw IOException("document path does not name a container")
    }

    private fun key(): String {
        val (key, complete) = input.string(DOCUMENT_KEY_CHARS)
        if (!complete) throw IOException("request document key is too long")
        input.expect(':')
        return key
    }

    private fun children(offset: Int): JsonObject {
        val array = input.take() == '['
        val end = if (array) ']' else '}'
        val children = ArrayList<JsonObject>()
        var index = 0
        input.space()
        while (input.peek() != end && children.size < DOCUMENT_PAGE_ENTRIES) {
            val name = if (array) index.toString() else key()
            if (index++ < offset) input.skip() else children += child(name)
            input.space()
            if (input.peek() != ',') break
            input.take()
            input.space()
        }
        val next = index.takeIf { moreChildren(end, children.size) }
        return buildJsonObject {
            put("type", if (array) "array" else "object")
            put("children", JsonArray(children))
            put("next", next)
        }
    }

    private fun moreChildren(end: Char, size: Int): Boolean =
        input.peek() != end && size == DOCUMENT_PAGE_ENTRIES

    private fun child(key: String): JsonObject {
        input.space()
        val value = when (input.peek()) {
            '{', '[' -> {
                val type = if (input.peek() == '{') "object" else "array"
                input.skip()
                buildJsonObject { put("type", type) }
            }
            '"' -> {
                val (preview, complete) = input.string(DOCUMENT_PREVIEW_CHARS)
                buildJsonObject { put("type", "string"); put("value", preview); put("complete", complete) }
            }
            else -> primitive()
        }
        return JsonObject(value + ("key" to JsonPrimitive(key)))
    }

    private fun primitive(): JsonObject {
        val value = try { Json.parseToJsonElement(input.atom()) } catch (failure: IllegalArgumentException) {
            throw IOException("invalid request document value", failure)
        }
        val type = if (value == JsonNull) {
            "null"
        } else if (value == JsonPrimitive(true) || value == JsonPrimitive(false)) {
            "boolean"
        } else {
            "number"
        }
        return buildJsonObject { put("type", type); put("value", value); put("complete", true) }
    }

    private fun text(offset: Int, quoted: Boolean): JsonObject {
        if (quoted) input.expect('"')
        repeat(offset) { if (nextText(quoted) == null) throw IOException("document text cursor is past its end") }
        val (result, ended) = textChunk(quoted)
        return buildJsonObject {
            put("type", if (quoted) "string" else "text")
            put("value", result)
            put("next", if (ended) null else offset + result.length)
        }
    }

    private fun nextText(quoted: Boolean): Char? =
        if (quoted) input.stringChar() else input.peek()?.let { input.take() }

    private fun textChunk(quoted: Boolean): Pair<String, Boolean> {
        val result = StringBuilder()
        var ended = false
        while (result.length < DOCUMENT_TEXT_CHARS && !ended) {
            val next = nextText(quoted)
            if (next == null) ended = true else result.append(next)
        }
        // Keep UTF-16 pairs together across independently encoded response pages.
        if (!ended && result.lastOrNull()?.isHighSurrogate() == true) nextText(quoted)?.let { result.append(it) }
        return result.toString() to ended
    }
}
