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
import splice.core.memory.HeapLease
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations

// why: how many large literals one pack remembers; a turn writes about four bodies, so four lets a conversation's
// client body and request body each find the one before them, and a second conversation costs only some reuse.
private const val TRACE_RECENT_LITERALS = 4

// why: a literal shorter than one largest chunk saves at most a chunk or two, so it is not worth its held text.
private const val TRACE_RECENT_MIN_CHARS = CHUNK_MAX

/** Where a literal's encoding picks up after the chunks it shares with a recent literal: [parts] are reused as they
 *  are, encoding restarts at char [from] of the text, and its first [skip] bytes already lie inside the last of them. */
internal data class TraceLiteralResume(val parts: List<JsonObject>, val from: Int, val skip: Int)

/** The last few large literals one pack wrote, each with the parts it was stored as. Each held text is charged to the
 *  heap while it is held; a text the heap cannot hold is simply not remembered. Encode runs on one file lane, so
 *  [resume] and the [remember] after it see no other literal between them. */
internal class TraceRecentLiterals(private val heap: HeapReservations) {
    private data class Recent(val text: String, val parts: JsonArray, val lease: HeapLease)

    private val held = ArrayDeque<Recent>()

    /** The recent literal [resume] built on, which the next [remember] replaces: a conversation keeps one entry. */
    private var base: Recent? = null

    /** The parts of the recent literal sharing the longest opening with [text], cut back to whole parts inside that
     *  opening that are still current in [pack], or null when there are none. */
    fun resume(text: String, pack: TraceBodyPack): TraceLiteralResume? {
        base = null
        val (best, common) = held.map { it to shared(it.text, text) }.maxByOrNull { it.second } ?: return null
        return plan(best, common, pack)?.also { base = best }
    }

    fun remember(text: String, parts: JsonArray) {
        base?.let { replaced -> if (held.remove(replaced)) replaced.lease.close() }
        base = null
        if (text.length < TRACE_RECENT_MIN_CHARS) return
        val lease = heap.reserve(text.length.toLong() * Char.SIZE_BYTES) ?: return
        held.addFirst(Recent(text, parts, lease).also { HeapOwners.keep(it, lease) })
        while (held.size > TRACE_RECENT_LITERALS) held.removeLast().lease.close()
    }

    fun clear() {
        held.forEach { it.lease.close() }
        held.clear()
        base = null
    }

    /** Whole parts inside the first [shared] chars' encoding, and where in the text the encoding resumes after them. */
    private fun plan(recent: Recent, shared: Int, pack: TraceBodyPack): TraceLiteralResume? {
        // The opening quote, then each shared char's bytes.
        var sharedBytes = 1L
        for (index in 0 until shared) sharedBytes += TraceLiteral.width(recent.text, index)
        val reused = ArrayList<JsonObject>()
        var end = 0L
        for (element in recent.parts) {
            val part = element as JsonObject
            val size = part.getValue("bytes").jsonPrimitive.int
            if (end + size > sharedBytes || !pack.current(part)) break
            reused += part
            end += size
        }
        if (reused.isEmpty()) return null
        // Back from the shared opening to the last char that starts at or before the last reused cut.
        var from = shared
        var at = sharedBytes
        while (at > end) at -= TraceLiteral.width(recent.text, --from)
        return TraceLiteralResume(reused, from, (end - at).toInt())
    }

    /** How many opening chars encode identically in both texts. A char's escape can depend on its neighbours
     *  (a surrogate pairs with the next or the one before), so the last char of the common prefix is not counted. */
    private fun shared(old: String, new: String): Int {
        val limit = minOf(old.length, new.length)
        var at = 0
        while (at < limit && old[at] == new[at]) at++
        return maxOf(0, at - 1)
    }
}
