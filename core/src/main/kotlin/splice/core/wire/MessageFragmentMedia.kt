package splice.core.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapJson

/** Canonical media equality, owned snapshots and retained-graph accounting share the same source fields. */
internal object MessageFragmentMedia {
    fun sameImage(before: ImageBlock, after: ContentBlock): Boolean =
        after is ImageBlock && sameSource(before.source, after.source)

    fun sameDocument(before: DocumentBlock, after: ContentBlock): Boolean {
        if (after !is DocumentBlock) return false
        return sameSource(before.source, after.source) && sameDescription(before, after) &&
            sameOptionalJson(before.citations, after.citations) &&
            sameOptionalJson(before.cacheControl, after.cacheControl)
    }

    private fun sameDescription(before: DocumentBlock, after: DocumentBlock): Boolean =
        before.title == after.title && before.context == after.context

    private fun sameSource(before: MediaSource?, after: MediaSource?): Boolean {
        if (before == null || after == null) return before == after
        return sameEncoding(before, after) && samePayload(before, after) &&
            sameOptionalJson(before.content, after.content)
    }

    private fun sameEncoding(before: MediaSource, after: MediaSource): Boolean =
        before.type == after.type && before.mediaType == after.mediaType

    private fun samePayload(before: MediaSource, after: MediaSource): Boolean =
        before.data == after.data && before.url == after.url

    private fun sameOptionalJson(before: JsonElement?, after: JsonElement?): Boolean =
        if (before == null || after == null) before == after else MessageFragmentInputs.same(before, after)

    fun freeze(block: ImageBlock): ImageBlock = block.copy(source = freezeSource(block.source))

    fun freeze(block: DocumentBlock): DocumentBlock = block.copy(
        source = freezeSource(block.source),
        citations = block.citations?.let { MessageFragmentInputs.freeze(it) as JsonObject },
        cacheControl = block.cacheControl?.let { MessageFragmentInputs.freeze(it) as JsonObject },
    )

    private fun freezeSource(source: MediaSource?): MediaSource? =
        source?.content?.let { source.copy(content = MessageFragmentInputs.freeze(it)) } ?: source

    fun bytes(block: DocumentBlock): Long {
        val text = HeapJson.add(block.title?.let(HeapJson::text) ?: 0L, block.context?.let(HeapJson::text) ?: 0L)
        val annotations = HeapJson.add(
            block.citations?.let(HeapJson::bytes) ?: 0L,
            block.cacheControl?.let(HeapJson::bytes) ?: 0L,
        )
        return HeapJson.add(bytes(block.source), HeapJson.add(text, annotations))
    }

    fun bytes(source: MediaSource?): Long {
        val fields = listOf(source?.type, source?.mediaType, source?.data, source?.url)
        val fieldsBytes = fields.fold(MEDIA_FRAGMENT_OWNER_BYTES) { total, text ->
            HeapJson.add(total, text?.let(HeapJson::text) ?: 0L)
        }
        return HeapJson.add(fieldsBytes, source?.content?.let(HeapJson::bytes) ?: 0L)
    }
}

// why: Keeps the existing conservative ownership charge for each retained canonical media source.
private const val MEDIA_FRAGMENT_OWNER_BYTES = 256L
