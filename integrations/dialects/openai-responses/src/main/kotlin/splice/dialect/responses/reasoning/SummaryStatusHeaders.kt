// NEW: V4-450 (2026-10-01) — a reasoning-summary part that is only a bold header is a status line, not
// reasoning. gpt-6-sol sends nothing else (108 of 108 parts in the Oct 1 trace, one reasoning item per
// header; one round carried 63 items with 9 distinct texts), and rendering each as its own thinking
// block drew a column of identical lines. codex-rs reads such a part the same way: its body-less header
// becomes the status line, never transcript (tui history_cell/messages.rs split_reasoning_summary_parts,
// chatwidget/streaming.rs latest_summary_line). Here a header-only part renders once per round per
// distinct text, and a part with a body streams live exactly as before.
package splice.dialect.responses.reasoning

import splice.upstream.transport.BufferCapacity

private const val MARKDOWN_BOLD = "**"

/** Summary text the fold renders now. [startsPart] marks a part's first rendered text, which takes
 *  the paragraph break when its thinking block already holds text. */
internal data class SummaryRelease(
    val text: String,
    val startsPart: Boolean,
    val complete: Boolean = false,
    val delivered: ResponsesTextRanges? = null,
) {
    /** A completed literal prefix cannot still become a comment placeholder. */
    fun canRender(held: ReasoningPrefixText): Boolean =
        if (complete) held.readable else !held.mayBeEmpty
}

/** One round's header decisions, keyed by the fold's reasoning keys. Each item's current summary part
 *  is held only while it can still turn out to be a bare header; the first character of a body
 *  releases it to stream live. */
internal class SummaryStatusHeaders(
    private val sequence: ReasoningPrefixSequence,
    private val owner: ResponsesTextOwner,
) {

    /** [seen] counts the part's delta characters, so a delta can be placed against [delivered]. */
    private data class Part(
        val delivered: ResponsesTextRanges,
        var held: ReasoningPrefixText? = null,
        var seen: Long = 0,
    )
    private class Item {
        val parts = LinkedHashMap<Int, Part>()
        var active = 0
        var next = 0
    }

    private val items = LinkedHashMap<Int, Item>()
    private var partCount = 0
    var pendingChars: Long = 0
        private set
    var revision: Long = 0
        private set

    /** Only held text owns capacity; delivery proofs borrow ranges of the existing thinking buffer. */
    private fun release(part: Part): String? {
        val held = part.held ?: return null
        part.held = null
        pendingChars -= held.length
        revision++
        return held.toString()
    }

    private val shown = HashSet<String>()
    val liveKeys: Set<Int> get() = items.keys

    /** A completed item's parts must use the same delivery proofs as its live summary events. */
    fun sawLive(key: Int): Boolean = key in items

    /** A new part begins: a previous part still held is decided first. */
    fun partAdded(key: Int, index: Int?): SummaryRelease? {
        val item = items.getOrPut(key) { Item() }
        val previous = item.parts[item.active]?.let { settle(it, doneText = null) }
        item.active = index ?: item.next
        part(item, item.active)
        return previous
    }

    fun delta(key: Int, text: String, index: Int?): SummaryRelease? {
        val item = items.getOrPut(key) { Item() }
        if (index != null) item.active = index
        val part = part(item, item.active)
        val start = part.seen
        part.seen += text.length
        if (part.delivered.length > 0) return beyondDelivered(part, text, start)
        val held = part.held ?: ReasoningPrefixText(sequence.next()).also { part.held = it }
        held.append(text)
        pendingChars += text.length
        revision++
        if (held.mayBeHeaderOnly || held.mayBeEmpty) return null
        return SummaryRelease(checkNotNull(release(part)), startsPart = true, delivered = part.delivered)
    }

    /** The part's completed text, authoritative over what was held. */
    fun done(key: Int, text: String, index: Int?): SummaryRelease? {
        val item = items.getOrPut(key) { Item() }
        return settle(part(item, index ?: item.active), text)
    }

    /** The item ended with a part still held, one no done event reached. */
    fun itemDone(key: Int): SummaryRelease? = items[key]?.let { item ->
        item.parts[item.active]?.let { settle(it, doneText = null) }
    }

    /** A completed item's text when no summary for it arrived live: refused only for a bare header
     *  this round already showed. */
    fun admitsLate(text: String): Boolean = ReadableReasoning.hasText(text) &&
        (!HeaderShape.isHeaderOnly(text) || shown.add(text.trim()))

    fun pending(): List<HeldReasoningPrefix> = items.flatMap { (key, item) ->
        item.parts.mapNotNull { (index, part) ->
            part.held?.let { HeldReasoningPrefix(key, summary = true, it, index) }
        }
    }

    fun discardPending() {
        items.clear()
        partCount = 0
        pendingChars = 0
        revision++
    }

    /** A visible boundary settles a part but leaves it live for any subsequent body deltas. */
    fun atBoundary(key: Int, index: Int?): SummaryRelease? = items[key]?.let { item ->
        item.parts[index ?: item.active]
    }?.let { part ->
        release(part)?.let { decide(it, part.delivered) }
    }

    /** The backend can send an item's done before its remaining deltas (openai/codex#16801). A completed
     *  copy may then have delivered past this delta, so only the characters beyond that proof render. */
    private fun beyondDelivered(part: Part, text: String, start: Long): SummaryRelease? {
        val skip = (part.delivered.length - start).coerceIn(0L, text.length.toLong()).toInt()
        if (skip == text.length) return null
        return SummaryRelease(text.substring(skip), startsPart = false, delivered = part.delivered)
    }

    private fun part(item: Item, index: Int): Part = item.parts.getOrPut(index) {
        require(
            index in 0 until BufferCapacity.MAX_TOOL_INDEX_ENTRIES &&
                partCount < BufferCapacity.MAX_TOOL_INDEX_ENTRIES,
        ) { "reasoning summary exceeds index capacity" }
        partCount++
        item.next = maxOf(item.next, index + 1)
        Part(owner.borrow())
    }

    private fun settle(part: Part, doneText: String?): SummaryRelease? {
        val held = release(part)
        val text = doneText ?: held ?: return null
        val delivered = part.delivered
        return when {
            delivered.length == 0 -> decide(text, delivered)
            !delivered.matchesPrefix(text) || text.length == delivered.length -> null
            else -> SummaryRelease(
                text.substring(delivered.length),
                startsPart = false,
                complete = true,
                delivered = delivered,
            )
        }
    }

    private fun decide(text: String, delivered: ResponsesTextRanges): SummaryRelease? = when {
        !ReadableReasoning.hasText(text) -> null
        HeaderShape.isHeaderOnly(text) && !shown.add(text.trim()) -> null
        else -> SummaryRelease(text, startsPart = true, complete = true, delivered = delivered)
    }
}

/** The shape of a summary part's leading bold run. */
private object HeaderShape {

    /** "**Header**" with nothing after it but whitespace or codex's empty-body marker. */
    fun isHeaderOnly(text: String): Boolean {
        val trimmed = text.trim()
        val close = closingBold(trimmed) ?: return false
        val body = trimmed.substring(close + MARKDOWN_BOLD.length).trim()
        return ReadableReasoning.isEmptyBody(body)
    }

    /** Index of the bold run's closing marker, when [text] opens with a non-empty bold run. */
    private fun closingBold(text: String): Int? {
        if (!text.startsWith(MARKDOWN_BOLD)) return null
        return text.indexOf(MARKDOWN_BOLD, MARKDOWN_BOLD.length).takeIf { it > MARKDOWN_BOLD.length }
    }
}
