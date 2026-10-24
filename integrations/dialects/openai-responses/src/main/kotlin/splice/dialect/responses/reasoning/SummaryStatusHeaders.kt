// NEW: V4-450 (2026-10-01) — a reasoning-summary part that is only a bold header is a status line, not
// reasoning. gpt-6-sol sends nothing else (108 of 108 parts in the Oct 1 trace, one reasoning item per
// header; one round carried 63 items with 9 distinct texts), and rendering each as its own thinking
// block drew a column of identical lines. codex-rs reads such a part the same way: its body-less header
// becomes the status line, never transcript (tui history_cell/messages.rs split_reasoning_summary_parts,
// chatwidget/streaming.rs latest_summary_line). Here a header-only part renders once per round per
// distinct text, and a part with a body streams live exactly as before.
package splice.dialect.responses.reasoning

private const val MARKDOWN_BOLD = "**"

/** Summary text the fold renders now. [startsPart] marks a part's first rendered text, which takes
 *  the paragraph break when its thinking block already holds text. */
internal data class SummaryRelease(val text: String, val startsPart: Boolean, val complete: Boolean = false) {
    /** A completed literal prefix cannot still become a comment placeholder. */
    fun canRender(held: ReasoningPrefixText): Boolean =
        if (complete) held.readable else !held.mayBeEmpty
}

/** One round's header decisions, keyed by the fold's reasoning keys. Each item's current summary part
 *  is held only while it can still turn out to be a bare header; the first character of a body
 *  releases it to stream live. */
internal class SummaryStatusHeaders(private val sequence: ReasoningPrefixSequence = ReasoningPrefixSequence()) {

    private data class Part(var held: ReasoningPrefixText? = null, var live: Boolean = false)

    private val current = LinkedHashMap<Int, Part>()
    var pendingChars: Long = 0
        private set
    var revision: Long = 0
        private set

    /** Only held text owns capacity; released parts keep their live identity without keeping another copy. */
    private fun release(part: Part): String? {
        val held = part.held ?: return null
        part.held = null
        part.live = true
        pendingChars -= held.length
        revision++
        return held.toString()
    }

    private val shown = HashSet<String>()
    private val liveKeys = HashSet<Int>()

    /** Whether a summary event for [key] arrived live: the completed item's text is then already
     *  decided and is not rendered a second time. */
    fun sawLive(key: Int): Boolean = key in liveKeys

    /** A new part begins: a previous part still held is decided first. */
    fun partAdded(key: Int): SummaryRelease? {
        liveKeys.add(key)
        val previous = settle(key, doneText = null)
        current[key] = Part()
        return previous
    }

    fun delta(key: Int, text: String): SummaryRelease? {
        liveKeys.add(key)
        val part = current.getOrPut(key) { Part() }
        if (part.live) return SummaryRelease(text, startsPart = false)
        val held = part.held ?: ReasoningPrefixText(sequence.next()).also { part.held = it }
        held.append(text)
        pendingChars += text.length
        revision++
        if (held.mayBeHeaderOnly) return null
        return SummaryRelease(checkNotNull(release(part)), startsPart = true)
    }

    /** The part's completed text, authoritative over what was held. */
    fun done(key: Int, text: String): SummaryRelease? {
        liveKeys.add(key)
        return settle(key, text)
    }

    /** The item ended with a part still held, one no done event reached. */
    fun itemDone(key: Int): SummaryRelease? = settle(key, doneText = null)

    /** A completed item's text when no summary for it arrived live: refused only for a bare header
     *  this round already showed. */
    fun admitsLate(text: String): Boolean = ReadableReasoning.hasText(text) &&
        (!HeaderShape.isHeaderOnly(text) || shown.add(text.trim()))

    fun pending(): List<HeldReasoningPrefix> = current.mapNotNull { (key, part) ->
        part.held?.let { HeldReasoningPrefix(key, summary = true, it) }
    }

    fun discardPending() {
        current.clear()
        pendingChars = 0
        revision++
    }

    /** A visible boundary settles a part but leaves it live for any subsequent body deltas. */
    fun atBoundary(key: Int): SummaryRelease? = current[key]?.let { part -> release(part)?.let(::decide) }

    private fun settle(key: Int, doneText: String?): SummaryRelease? {
        val part = current.remove(key)
        val wasLive = part?.live == true
        val held = part?.let(::release)
        return when {
            part == null -> doneText?.let { decide(it) }
            wasLive -> null
            else -> decide(doneText ?: held.orEmpty())
        }
    }

    private fun decide(text: String): SummaryRelease? = when {
        !ReadableReasoning.hasText(text) -> null
        HeaderShape.isHeaderOnly(text) && !shown.add(text.trim()) -> null
        else -> SummaryRelease(text, startsPart = true, complete = true)
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
