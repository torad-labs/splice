// NEW: trace cost (Oct 13, splice-lead through splice-runtime-lead). A conversation's next request repeats its whole
// history before the turn's new messages, and every byte of it was escaped, Gear-scanned and hashed again each turn,
// to find chunks the pack already held. A body now reuses the stored chunks that lie inside the opening it shares with
// a recent body of the same pack, and only what follows is encoded, chunked and hashed.
//
// The reuse is exact. The Gear hash restarts at every cut, so where a stream cuts depends only on the bytes since its
// previous cut: two streams with the same opening bytes cut in the same places through that opening. The reused parts
// are therefore the parts a whole re-encode would have produced, and the chunker resumes at the last reused cut.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import splice.core.memory.HeapCategory
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations

// why: how many large literals one pack remembers; a turn writes about four bodies, so four lets a conversation's
// client body and request body each find the one before them, and a second conversation costs only some reuse.
private const val TRACE_RECENT_LITERALS = 4

// why: a literal shorter than one largest chunk saves at most a chunk or two, so it is not worth its held text.
private const val TRACE_RECENT_MIN_CHARS = CHUNK_MAX

// why: two texts are compared 1K chars at a time with one bulk compare, and only the block that differs per char.
private const val TRACE_SHARED_BLOCK_CHARS = 1024

/** Where a literal's encoding picks up after the chunks it shares with a recent literal: [parts] are reused as they
 *  are, encoding restarts at char [from] of the text, and its first [skip] bytes already lie inside the last of them. */
internal data class TraceLiteralResume(val parts: List<JsonObject>, val from: Int, val skip: Int)

/** The last few large literals one pack wrote, each with the parts it was stored as and, for each part, where the
 *  text's encoding picks up after it. Each held text is charged to the heap while it is held; a text the heap cannot
 *  hold is simply not remembered. Encode runs on one file lane, so [resume] and the [remember] after it see no other
 *  literal between them.
 *
 *  Trace cost (Oct 13): a resume reads the marks of the parts it reuses, and a remembered literal inherits them and
 *  measures only the text after them, so no turn re-measures the history it shares with the one before.
 *  Trace cost (Oct 15): a new part's mark is measured from the char its chunker's cut names (TraceCuts), at most a
 *  batch of text before the cut, so each part re-measures about 1 KiB of text rather than all of it. */
internal class TraceRecentLiterals(private val heap: HeapReservations) {
    /** [marks] holds, for part k, the char where encoding resumes after it, shifted left by [MARK_SKIP_BITS], with the
     *  bytes of that char's encoding already inside the part in the low bits. */
    private class Recent(val text: String, val parts: JsonArray, val lease: HeapLease, val marks: LongArray) {
        fun from(part: Int): Int = (marks[part] ushr MARK_SKIP_BITS).toInt()

        fun skip(part: Int): Int = (marks[part] and MARK_SKIP_MASK).toInt()

        /** Part [part]'s bytes are all encoded from chars below [shared], which both texts encode alike. */
        fun inside(part: Int, shared: Int): Boolean =
            from(part) < shared || (from(part) == shared && skip(part) == 0)
    }

    /** What [resume] built on, which the next [remember] replaces and inherits marks from. */
    private data class Resumed(val base: Recent, val reused: Int, val end: Long)

    private val held = ArrayDeque<Recent>()

    private var resumed: Resumed? = null

    /** The parts of the recent literal sharing the longest opening with [text], cut back to whole parts inside that
     *  opening that are still current in [pack], or null when there are none. */
    fun resume(text: String, pack: TraceBodyPack): TraceLiteralResume? {
        resumed = null
        val (best, common) = held.map { it to shared(it.text, text) }.maxByOrNull { it.second } ?: return null
        var reused = 0
        var end = 0L
        while (reused < best.parts.size && best.inside(reused, common)) {
            val part = best.parts[reused] as JsonObject
            if (!pack.current(part)) break
            end += part.getValue("bytes").jsonPrimitive.int
            reused++
        }
        if (reused == 0) return null
        resumed = Resumed(best, reused, end)
        val last = reused - 1
        val shared = best.parts.subList(0, reused).map { it as JsonObject }
        return TraceLiteralResume(shared, best.from(last), best.skip(last))
    }

    /** Holds [text], whose [parts] past the resumed ones were cut where [cuts] says. */
    fun remember(text: String, parts: JsonArray, cuts: TraceCuts) {
        val prior = resumed
        resumed = null
        if (text.length < TRACE_RECENT_MIN_CHARS) return
        val lease = heap.reserve(
            text.length.toLong() * Char.SIZE_BYTES + parts.size.toLong() * Long.SIZE_BYTES,
            HeapCategory.RECENT_HISTORY,
            HeapOwnerState.ACTIVE,
        ) ?: return
        var remembered = false
        try {
            val marks = marks(text, parts, prior, cuts)
            held.addFirst(Recent(text, parts, lease, marks).also { HeapOwners.keep(it, lease) })
            remembered = true
        } finally {
            if (!remembered) lease.close()
        }
        // Inherited marks remain charged until their copy belongs to the new entry.
        prior?.base?.let { replaced -> if (held.remove(replaced)) replaced.lease.ownerFinished() }
        while (held.size > TRACE_RECENT_LITERALS) held.removeLast().lease.ownerFinished()
    }

    fun clear() {
        held.forEach { it.lease.ownerFinished() }
        held.clear()
        resumed = null
    }

    /** Each part's mark: the last char that starts at or before the part's end, and the bytes of it the part holds. A
     *  pair's low half encodes to nothing (its high half carries all four bytes), so a mark never lands on one: a UTF-8
     *  writer started on a low half writes a replacement byte instead of the pair. Inherited marks are copied as they
     *  are. A new part's measuring starts at its cut's origin when that lies ahead: every char before the origin ends
     *  at or before it, inside this part, so measuring them would only consume them. */
    private fun marks(text: String, parts: JsonArray, prior: Resumed?, cuts: TraceCuts): LongArray {
        val marks = LongArray(parts.size)
        var part = 0
        var end = 0L
        // The stream opens with the literal's quote, one byte before char 0.
        var from = 0
        var at = 1L
        // The encoder's first fed byte: the opening quote, or the first byte of the char a resume restarted at.
        var fedBase = 0L
        if (prior != null) {
            prior.base.marks.copyInto(marks, 0, 0, prior.reused)
            part = prior.reused
            end = prior.end
            from = prior.base.from(part - 1)
            at = end - prior.base.skip(part - 1)
            fedBase = at
        }
        val reused = part
        while (part < parts.size) {
            val cut = part - reused
            end += cuts.length(cut)
            if (cuts.originChar(cut) > from) {
                from = cuts.originChar(cut)
                at = fedBase + cuts.originFed(cut)
            }
            while (from < text.length) {
                val width = TraceLiteral.width(text, from)
                if (at + width > end) break
                at += width
                // At a pair's exact end, consume its zero-width low half before recording the next start.
                from++
            }
            // The terminal part includes the closing quote; its mark lies beyond the shared character prefix.
            marks[part++] = (from.toLong() shl MARK_SKIP_BITS) or (end - at)
        }
        return marks
    }

    /** How many opening chars encode identically in both texts. A char's escape can depend on its neighbours
     *  (a surrogate pairs with the next or the one before), so the last char of the common prefix is not counted. */
    private fun shared(old: String, new: String): Int {
        val limit = minOf(old.length, new.length)
        var at = 0
        while (at + TRACE_SHARED_BLOCK_CHARS <= limit && old.regionMatches(at, new, at, TRACE_SHARED_BLOCK_CHARS)) {
            at += TRACE_SHARED_BLOCK_CHARS
        }
        while (at < limit && old[at] == new[at]) at++
        return maxOf(0, at - 1)
    }
}

// why: a mark's low bits hold the bytes of one char already inside a part: under six, the longest escape, so 3 bits.
private const val MARK_SKIP_BITS = 3

// why: the low MARK_SKIP_BITS bits of a mark.
private const val MARK_SKIP_MASK = (1L shl MARK_SKIP_BITS) - 1
