// NEW: Oct 11, 2026 — the retained lines of one generation as records in a few large byte chunks.
package splice.app.sources

// why: a chunk of sixteen kibibytes holds about two hundred and fifty records of the busiest command, so a chunk
// freed on eviction gives back its bytes in small steps, and a generation's unused tail is never much.
private const val CHUNK_BYTES = 16 * 1_024

// Covers the chunk object, its array header and its place in the queue.
private const val CHUNK_OVERHEAD_BYTES = 96L

/** A run of records, and the difference base its first record is read from. */
private class PerfDenseChunk(capacity: Int, firstBase: PerfDenseState) {
    val data = ByteArray(capacity)
    var used = 0
    var count = 0

    /** Where reading starts: the base the next unread record is a difference from. */
    var head = 0
    val headBase = firstBase

    val charge: Long get() = data.size + CHUNK_OVERHEAD_BYTES

    fun fits(size: Int): Boolean = used + size <= data.size
}

/** Records, oldest first. The unterminated last line, which is read again, is kept as an object. */
internal class PerfDenseEntries(
    private val codec: PerfDenseCodec,
    private val generation: PerfGenerationRef,
) : PerfEntryList {
    private val chunks = ArrayDeque<PerfDenseChunk>()
    private var tail: PerfCachedEntry? = null

    /** The base after the last record written. */
    private val running = PerfDenseState()

    override var size = 0
        private set
    override var bytes = 0L
        private set

    override fun iterator(): Iterator<PerfCachedEntry> = object : Iterator<PerfCachedEntry> {
        private val queue = chunks.toList()
        private var chunk = 0
        private var cursor: PerfByteCursor? = null
        private var base = PerfDenseState()
        private var remaining = 0
        private var pending = tail

        override fun hasNext(): Boolean {
            while (remaining == 0 && chunk < queue.size) {
                val next = queue[chunk++]
                cursor = PerfByteCursor(next.data, next.head)
                base = next.headBase.copy()
                remaining = next.count
            }
            return remaining > 0 || pending != null
        }

        override fun next(): PerfCachedEntry {
            if (!hasNext()) throw NoSuchElementException()
            if (remaining == 0) return requireNotNull(pending).also { pending = null }
            remaining--
            return codec.decode(requireNotNull(cursor), base, generation)
        }
    }

    override fun addLast(entry: PerfCachedEntry) {
        if (entry.raw != null) {
            tail = entry
            size++
            bytes += entry.retainedBytes
            return
        }
        flushTail()
        val before = running.copy()
        val record = codec.encode(entry, running)
        val chunk = chunks.lastOrNull()?.takeIf { it.fits(record.size) } ?: newChunk(record.size, before)
        record.copyInto(chunk.data, chunk.used)
        chunk.used += record.size
        chunk.count++
        size++
    }

    /** A last line that is followed by more lines was terminated after all; it is written as a record. */
    private fun flushTail() {
        val held = tail ?: return
        tail = null
        size--
        bytes -= held.retainedBytes
        addLast(held.copy(raw = null))
    }

    private fun newChunk(needed: Int, base: PerfDenseState): PerfDenseChunk {
        val chunk = PerfDenseChunk(maxOf(CHUNK_BYTES, needed), base.copy())
        chunks.addLast(chunk)
        bytes += chunk.charge
        return chunk
    }

    override fun removeFirst(): PerfCachedEntry {
        val chunk = chunks.firstOrNull() ?: return removeTail()
        val cursor = PerfByteCursor(chunk.data, chunk.head)
        val entry = codec.decode(cursor, chunk.headBase, generation)
        chunk.head = cursor.position
        chunk.count--
        size--
        if (chunk.count == 0) {
            chunks.removeFirst()
            bytes -= chunk.charge
        }
        return entry
    }

    private fun removeTail(): PerfCachedEntry {
        val held = tail ?: throw NoSuchElementException()
        tail = null
        size--
        bytes -= held.retainedBytes
        return held
    }

    override fun lastOrNull(): PerfCachedEntry? = tail ?: lastRecord()?.first

    /** The last record, decoded by reading its chunk from the head, with the position it starts at and the base before it. */
    private fun lastRecord(): Triple<PerfCachedEntry, Int, PerfDenseState>? {
        val chunk = chunks.lastOrNull() ?: return null
        val cursor = PerfByteCursor(chunk.data, chunk.head)
        val base = chunk.headBase.copy()
        var before = base.copy()
        var at = cursor.position
        var last: PerfCachedEntry? = null
        repeat(chunk.count) {
            at = cursor.position
            before = base.copy()
            last = codec.decode(cursor, base, generation)
        }
        return last?.let { Triple(it, at, before) }
    }

    override fun removeLast(): PerfCachedEntry {
        if (tail != null) return removeTail()
        val (entry, at, before) = lastRecord() ?: throw NoSuchElementException()
        val chunk = chunks.last()
        chunk.used = at
        chunk.count--
        size--
        running.ts = before.ts
        running.end = before.end
        if (chunk.count == 0) {
            chunks.removeLast()
            bytes -= chunk.charge
        }
        return entry
    }

    override fun extendLast(at: Long) {
        val held = tail
        if (held != null) {
            if (held.end == at) held.end++
            return
        }
        val last = lastOrNull()?.takeIf { it.end == at } ?: return
        removeLast()
        last.end++
        addLast(last)
    }

    override fun clear() {
        chunks.clear()
        tail = null
        size = 0
        bytes = 0L
        running.ts = 0L
        running.end = 0L
    }
}
