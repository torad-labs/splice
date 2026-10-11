// NEW: V4-343 — is one line exactly one JSON object? Read as bytes, never decoded, for TraceTail's count of a
// trace store. kotlinx rebuilt every record's multi-megabyte body strings only to skip them, 62% of the
// console trace page's 21.4 s on claudex's store (2026-09-26); this walks the bytes once by RFC 8259's
// grammar and keeps only the raw text of the few top-level members its reader names.
//
// It is a second author of "this line is JSON", beside kotlinx, so it is strict on purpose and answers only
// for lines kotlinx also parses: a line that is not strictly one object, or whose named members it cannot
// vouch for (one repeated, one holding an object or array, one past MAX_CAPTURE_BYTES, or any top-level key
// written with an escape, which could decode to a named one), is DECLINED, and the reader asks kotlinx as it
// always did. V4-343's fuzz cell holds that direction on thousands of damaged records. JsonLineScan walks structure,
// JsonLineMembers captures named stamps, and JsonTokens reads the scalars.
package splice.head.trace

import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.storage.ByteWords
import splice.upstream.memory.JvmHeap
import java.io.InputStream

// why: the bytes read per call, a few pages: a 2-3 MB record streams through a buffer fifty times smaller
private const val READ_BYTES = 64 * 1024

/** Reads lines as JSON objects, keeping the raw text of the top-level members named [captured]. One per
 *  reader; its buffer is reused from line to line. */
internal class JsonLineShape(captured: Set<String>, private val heap: HeapReservations = JvmHeap.budget) {
    private val names = captured.map { it to it.toByteArray() }

    // The streaming buffer also covers the bounded named-member captures that coexist with it.
    private val bufferLease = heap.reserve(READ_BYTES * 2L) ?: throw HeapCapacityException()
    private val words = ByteWords.view(ByteArray(READ_BYTES).also { HeapOwners.keep(it, bufferLease) })

    /** The raw text of each named member of the one JSON object [input] holds (a name it lacks is absent), or
     *  null when this cannot vouch for the line (see the header). Reads [input] until its end or the decline. */
    fun members(input: InputStream): Map<String, String>? {
        val scan = JsonLineScan(names, heap)
        val buffer = words.array()
        var n = input.read(buffer)
        while (n >= 0) {
            if (!scan.feed(words, n)) return null
            n = input.read(buffer)
        }
        return scan.finish()
    }
}
