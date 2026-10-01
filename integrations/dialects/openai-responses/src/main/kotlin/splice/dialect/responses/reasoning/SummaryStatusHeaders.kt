// NEW: V4-450 (2026-10-01) — a reasoning-summary part that is only a bold header is a status line, not
// reasoning. gpt-6-sol sends nothing else (108 of 108 parts in the Oct 1 trace, one reasoning item per
// header; one round carried 63 items with 9 distinct texts), and rendering each as its own thinking
// block drew a column of identical lines. codex-rs reads such a part the same way: its body-less header
// becomes the status line, never transcript (tui history_cell/messages.rs split_reasoning_summary_parts,
// chatwidget/streaming.rs latest_summary_line). Here a header-only part renders once per round per
// distinct text, and a part with a body streams live exactly as before.
package splice.dialect.responses.reasoning

private const val MARKDOWN_BOLD = "**"

// codex's marker for a header whose body is deliberately empty (split_reasoning_summary_parts).
private const val EMPTY_BODY = "<!-- -->"

// A header is a few words; a part still open in bold past this is prose and is released at once.
private const val MAX_HEADER_CHARS = 200

/** Summary text the fold renders now. [startsPart] marks a part's first rendered text, which takes
 *  the paragraph break when its thinking block already holds text. */
internal data class SummaryRelease(val text: String, val startsPart: Boolean)

/** One round's header decisions, keyed by the fold's reasoning keys. Each item's current summary part
 *  is held only while it can still turn out to be a bare header; the first character of a body
 *  releases it to stream live. */
internal class SummaryStatusHeaders {

    private class Part {
        val held = StringBuilder()
        var live = false
    }

    private val current = HashMap<Int, Part>()
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
        part.held.append(text)
        if (HeaderShape.mayStillBeHeaderOnly(part.held.toString())) return null
        part.live = true
        return SummaryRelease(part.held.toString(), startsPart = true)
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
    fun admitsLate(text: String): Boolean = !HeaderShape.isHeaderOnly(text) || shown.add(text.trim())

    private fun settle(key: Int, doneText: String?): SummaryRelease? {
        val part = current.remove(key)
        return when {
            part == null -> doneText?.let { decide(it) }
            part.live -> null
            else -> decide(doneText ?: part.held.toString())
        }
    }

    private fun decide(text: String): SummaryRelease? = when {
        text.isBlank() -> null
        HeaderShape.isHeaderOnly(text) && !shown.add(text.trim()) -> null
        else -> SummaryRelease(text, startsPart = true)
    }
}

/** The shape of a summary part's leading bold run. */
private object HeaderShape {

    /** "**Header**" with nothing after it but whitespace or codex's empty-body marker. */
    fun isHeaderOnly(text: String): Boolean {
        val trimmed = text.trim()
        val close = closingBold(trimmed) ?: return false
        val body = trimmed.substring(close + MARKDOWN_BOLD.length).trim()
        return body.isEmpty() || body == EMPTY_BODY
    }

    /** Whether more deltas could still make [held] a header-only part. */
    fun mayStillBeHeaderOnly(held: String): Boolean {
        val text = held.trimStart()
        return when {
            text.length > MAX_HEADER_CHARS -> false
            text.length < MARKDOWN_BOLD.length -> MARKDOWN_BOLD.startsWith(text)
            !text.startsWith(MARKDOWN_BOLD) -> false
            else -> {
                val close = closingBold(text)
                close == null || EMPTY_BODY.startsWith(text.substring(close + MARKDOWN_BOLD.length).trim())
            }
        }
    }

    /** Index of the bold run's closing marker, when [text] opens with a non-empty bold run. */
    private fun closingBold(text: String): Int? {
        if (!text.startsWith(MARKDOWN_BOLD)) return null
        return text.indexOf(MARKDOWN_BOLD, MARKDOWN_BOLD.length).takeIf { it > MARKDOWN_BOLD.length }
    }
}
