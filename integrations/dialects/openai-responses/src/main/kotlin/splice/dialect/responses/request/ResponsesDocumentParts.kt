package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.DocumentBlock
import splice.core.wire.MediaSource
import splice.dialect.responses.ResponsesQuirks

/**
 * Public document bodies expand in place. Literal strings are never parsed as serialized blocks.
 * Titles and context precede the body as text, never filenames or media-type guesses.
 * Cache breakpoints are source-side hints with no Responses field. Enabled source citations require
 * request-admission refusal; neither that configuration nor disabled citations become content fields.
 */
internal class ResponsesDocumentParts(private val quirks: ResponsesQuirks) {
    private val parts = ResponsesInputParts(quirks.request.minImageEdgePx)

    /** Null preserves the walk's existing whole-document omission when there is no metadata to retain. */
    fun message(block: DocumentBlock): List<JsonObject>? {
        val body = convert(block.source, null)
        val metadata = metadata(block)
        if (body == null && metadata.isEmpty()) return null
        val missing = block.source?.mediaType ?: "unknown type"
        return metadata + (body ?: listOf(omitted(DOCUMENT_KIND, null, missing)))
    }

    fun toolResult(block: DocumentBlock, toolUseId: String): List<JsonObject> {
        val body = convert(block.source, toolUseId)
            ?: listOf(omitted(DOCUMENT_KIND, toolUseId, fallbackReason()))
        return metadata(block) + body
    }

    private fun metadata(block: DocumentBlock): List<JsonObject> = buildList {
        block.title?.let { add(text(it)) }
        block.context?.let { add(text(it)) }
    }

    private fun convert(source: MediaSource?, toolUseId: String?): List<JsonObject>? = when (source?.type) {
        TEXT_KIND -> source.data?.let { listOf(text(it)) }
        "file" -> listOf(
            omitted(DOCUMENT_KIND, toolUseId, "Anthropic file references are not available on this backend"),
        )
        "content" -> contentParts(source.content, toolUseId)
        else -> fileParts(source, toolUseId)
    }

    private fun contentParts(content: JsonElement?, toolUseId: String?): List<JsonObject>? = when (content) {
        is JsonPrimitive -> content.takeIf { it.isString }?.let { listOf(text(it.content)) }
        is JsonArray -> content.map { contentPart(it, toolUseId) }
        is JsonObject, null -> null
    }

    private fun fileParts(source: MediaSource?, toolUseId: String?): List<JsonObject>? {
        if (!quirks.request.supportsInputFiles) return null
        if (toolUseId != null && !quirks.request.supportsStructuredToolOutput) return null
        return parts.filePart(source)?.let { listOf(it) }
    }

    private fun fallbackReason(): String = when {
        !quirks.request.supportsStructuredToolOutput -> STRUCTURED_UNSUPPORTED
        !quirks.request.supportsInputFiles -> FILE_UNSUPPORTED
        else -> UNSUPPORTED_SOURCE
    }

    private fun contentPart(content: JsonElement, toolUseId: String?): JsonObject {
        val block = content as? JsonObject
        return when (string(block, FIELD_TYPE)) {
            TEXT_KIND -> string(block, FIELD_TEXT)?.let(::text)
                ?: omitted(DOCUMENT_KIND, toolUseId, UNSUPPORTED_SOURCE)
            IMAGE_KIND -> imagePart(block?.get("source") as? JsonObject, toolUseId)
            else -> omitted(DOCUMENT_KIND, toolUseId, UNSUPPORTED_SOURCE)
        }
    }

    private fun imagePart(raw: JsonObject?, toolUseId: String?): JsonObject {
        val source = raw?.let {
            MediaSource(
                type = string(it, FIELD_TYPE).orEmpty(),
                mediaType = string(it, "media_type"),
                data = string(it, "data"),
                url = string(it, "url"),
            )
        }
        val floor = parts.belowFloor(source)
        return when {
            floor != null -> omitted(IMAGE_KIND, toolUseId, parts.floorReason(floor))
            toolUseId != null && !quirks.request.supportsStructuredToolOutput ->
                omitted(IMAGE_KIND, toolUseId, STRUCTURED_UNSUPPORTED)
            else -> parts.imagePart(source) ?: omitted(IMAGE_KIND, toolUseId, UNSUPPORTED_SOURCE)
        }
    }

    private fun omitted(kind: String, toolUseId: String?, reason: String): JsonObject {
        val subject = if (toolUseId == null) {
            kind
        } else {
            val count = if (kind == IMAGE_KIND) "1 image(s)" else kind
            "$count from tool_result $toolUseId"
        }
        return text("[$subject omitted by ${quirks.providerTag} proxy: $reason]")
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put(FIELD_TYPE, "input_text")
        put(FIELD_TEXT, value)
    }

    private fun string(value: JsonObject?, field: String): String? =
        (value?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private const val STRUCTURED_UNSUPPORTED = "structured tool output unsupported"
private const val FILE_UNSUPPORTED = "input_file unsupported by this backend"
private const val DOCUMENT_KIND = "document"
private const val IMAGE_KIND = "image"
private const val TEXT_KIND = "text"
private const val FIELD_TYPE = "type"
private const val FIELD_TEXT = "text"
