// NEW: one active day's chunk and weak-literal indexes own their charged capacity.
// 2026-10-05: chunks are indexed in two primitive arrays, a digest prefix to an entry's offset, under a hard entry
// cap. Each entry was a map node holding a hex key and a JSON reference charged at 1 KiB, and a v2 pack holds about
// 220 thousand entries when its day fills 1 GiB: about 220 MiB of heap per head, on a 2 GiB daemon.
package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.upstream.memory.JvmHeap
import java.util.HexFormat
import java.util.UUID
import java.util.WeakHashMap

// why: a hash key, JSON reference fields, map nodes and table growth coexist for each weak-literal entry.
private const val TRACE_INDEX_ENTRY_BYTES = 1024L

// why: the most chunks one pack indexes, so its table stops at 2^20 slots of two longs, 16 MiB per head. The
// 2026-10-05 traffic fills a 1 GiB pack at about 220 thousand entries, so only a day of tiny entries meets this.
internal const val TRACE_PACK_MAX_ENTRIES: Int = 1 shl 19

// why: the first table; it doubles whenever it would pass half full.
private const val TRACE_INDEX_FIRST_SLOTS = 1 shl 10

// why: two longs per slot, the digest prefix and the entry's offset.
private const val TRACE_INDEX_SLOT_BYTES = 2L * Long.SIZE_BYTES

// why: a digest's first 16 hex digits are its first 8 bytes, which key the slot.
private const val TRACE_INDEX_KEY_DIGITS = 16

/** Readers never share this writer-local index or retain a global body cache. A slot names where an entry with that
 *  digest prefix lies; the writer re-reads the entry before reusing it, so a colliding prefix costs a fresh append,
 *  never a wrong body. */
internal class TracePackIndex(val heap: HeapBudget = JvmHeap.budget) {
    var generation: UUID? = null
    var end: Long = TRACE_PACK_START_BYTES.toLong()
    var tail: JsonObject? = null

    // Weak keys reuse queued equal literals without keeping completed request bodies alive.
    val literals = WeakHashMap<String, JsonArray>()
    private val literalLease = HeapOwners.charge(literals, heap, 0L)
    private var keys = LongArray(0)
    private var offsets = LongArray(0)

    // The index outlives every table it grows through, so it owns the table's charge.
    private val tableLease = HeapOwners.charge(this, heap, 0L)

    /** How many chunks the table names. */
    var size: Int = 0
        private set

    /** Past this, the pack takes no new entry, however many bytes are left. */
    val full: Boolean get() = size >= TRACE_PACK_MAX_ENTRIES

    /** Where the entry for [hash] lay when it was written or scanned, or null for a digest the table never met. */
    fun offset(hash: String): Long? {
        if (size == 0) return null
        val key = key(hash)
        var slot = slot(key)
        while (keys[slot] != 0L) {
            if (keys[slot] == key) return offsets[slot]
            slot = (slot + 1) and (keys.size - 1)
        }
        return null
    }

    /** Room for one more entry: a full pack refuses it, and a table past half full doubles inside the budget. */
    fun admit() {
        if (full) throw TracePackFull()
        if ((size + 1L) * 2 <= keys.size) return
        val slots = maxOf(TRACE_INDEX_FIRST_SLOTS, keys.size * 2)
        if (!tableLease.resize(slots * TRACE_INDEX_SLOT_BYTES)) throw HeapCapacityException()
        val oldKeys = keys
        val oldOffsets = offsets
        keys = LongArray(slots)
        offsets = LongArray(slots)
        size = 0
        for (slot in oldKeys.indices) {
            if (oldKeys[slot] != 0L) place(oldKeys[slot], oldOffsets[slot], replace = true)
        }
    }

    /** Names [offset] for [hash]; an admitted entry replaces what a colliding or stale slot named. */
    fun put(hash: String, offset: Long) = place(key(hash), offset, replace = true)

    /** Names [offset] for [hash] unless an earlier entry already holds that digest. */
    fun putIfAbsent(hash: String, offset: Long) = place(key(hash), offset, replace = false)

    fun clear() {
        keys.fill(0L)
        offsets.fill(0L)
        size = 0
        literals.clear()
    }

    fun cache(text: String, parts: JsonArray) {
        val needed = (literals.size + 1L) * TRACE_INDEX_ENTRY_BYTES
        if (!literalLease.resize(maxOf(literalLease.bytes, needed))) throw HeapCapacityException()
        HeapOwners.charge(parts, heap, HeapJson.bytes(parts))
        literals[text] = parts
    }

    private fun place(key: Long, offset: Long, replace: Boolean) {
        var slot = slot(key)
        while (keys[slot] != 0L && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
        if (keys[slot] == 0L) {
            keys[slot] = key
            size++
        } else if (!replace) {
            return
        }
        offsets[slot] = offset
    }

    private fun slot(key: Long): Int = (key xor (key ushr Int.SIZE_BITS)).toInt() and (keys.size - 1)

    /** A zero prefix would read as an empty slot, so it shares a key with one; the writer's re-read tells them
     *  apart. */
    private fun key(hash: String): Long =
        HexFormat.fromHexDigitsToLong(hash, 0, TRACE_INDEX_KEY_DIGITS).takeIf { it != 0L } ?: 1L
}
