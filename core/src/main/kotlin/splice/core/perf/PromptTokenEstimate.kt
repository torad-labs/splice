// NEW: V4-446 — the existing local count_tokens byte estimate, shared with Codex preflight.
package splice.core.perf

import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock
import splice.core.wire.ContentBlock.DocumentBlock
import splice.core.wire.ContentBlock.ImageBlock
import splice.core.wire.ContentBlock.ToolResultBlock

// why: the local count_tokens endpoint uses three UTF-8 bytes per estimated input token.
private const val ESTIMATE_BYTES_PER_TOKEN: Long = 3

// why: a base64 image or document costs the model a few thousand tokens at most, however many megabytes its text is.
// 1600 is Anthropic's documented per-image ceiling, used for each image and document block.
private const val ESTIMATE_TOKENS_PER_MEDIA: Long = 1_600

// why: a document whose source is plain text (or a list of text blocks) is read as text, so it stays counted by its bytes.
private const val TEXT_SOURCE: String = "text"
private const val CONTENT_SOURCE: String = "content"

/** An estimate, not a tokenizer or a hard upper bound; exact input comes from a completed turn. */
public object PromptTokenEstimate {
    public fun fromBytes(bytes: Long): Long = ((bytes + ESTIMATE_BYTES_PER_TOKEN - 1) / ESTIMATE_BYTES_PER_TOKEN)
        .coerceAtLeast(1)

    /** [fromBytes] for a whole request body of [totalBytes], with each image and non-text document counted at a flat
     *  media cost instead of by the length of its base64 text, which would put a 1 MB screenshot near 350,000 tokens. */
    public fun forRequest(totalBytes: Long, request: AnthropicRequest): Long {
        val media = request.messages.flatMap { mediaDataLengths(it.content) }
        val textBytes = (totalBytes - media.sum()).coerceAtLeast(0)
        return fromBytes(textBytes) + media.size * ESTIMATE_TOKENS_PER_MEDIA
    }

    private fun mediaDataLengths(blocks: List<ContentBlock>): List<Long> =
        blocks.filterIsInstance<ImageBlock>().map { (it.source?.data?.length ?: 0).toLong() } +
            blocks.filterIsInstance<DocumentBlock>()
                .filterNot { it.source?.type == TEXT_SOURCE || it.source?.type == CONTENT_SOURCE }
                .map { (it.source?.data?.length ?: 0).toLong() } +
            blocks.filterIsInstance<ToolResultBlock>().flatMap { mediaDataLengths(it.content) }
}
