// NEW: bounded append-aware compact perf retention, with no second on-disk format.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.FileTime
import java.util.LinkedList

/** Per-source byte ceiling: the synthetic 27,537-row daily shape fits without retaining all archives. */
internal const val PERF_CACHE_BYTES: Long = 64L * 1_024 * 1_024

// Keeps more than twice the measured largest daily source while bounding tiny or invalid records.
internal const val PERF_CACHE_ROWS: Int = 65_536

// Unknown field names cannot retain more than 1 MiB of shared names per source.
private const val FIELD_NAME_BYTES = 1_024L * 1_024

// Bounds identities and metadata independently of the number of archived generations on disk.
private const val CACHE_GENERATIONS = 64

// Small custom caches reserve at most one sixteenth of their ceiling for shared field names.
private const val FIELD_NAME_BUDGET_DIVISOR = 16L

// Covers generation, identity, linked-list and map objects, digest and amortized map capacity.
private const val GENERATION_OVERHEAD_BYTES = 1_024L

// Covers the fixed 8 KiB hash buffer, digest provider and cache objects with alignment headroom.
private const val CACHE_OVERHEAD_BYTES = 12L * 1_024

// Covers the path's byte/string forms and fallback normalized identity with worst-case UTF-16.
private const val PATH_STORAGE_BYTES_PER_CHAR = 8L

/** A bounded, append-aware recent suffix. Older evicted prefixes keep the original on-demand path.
 *  The byte charge includes compact records, field-name sharing, generation metadata and a pending
 *  last line. Returned windows and one input-line decode are transient/caller memory, as before. */
internal class PerfRowsCache(private val limitBytes: Long = PERF_CACHE_BYTES) {
    init {
        require(limitBytes >= CACHE_OVERHEAD_BYTES)
    }

    private val names = PerfFieldNames(minOf(limitBytes / FIELD_NAME_BUDGET_DIVISOR, FIELD_NAME_BYTES))
    private val generations = LinkedHashMap<Any, PerfCachedGeneration>()
    private var recordBytes = 0L
    private var recordCount = 0
    private val prefix = PerfPrefixDigest()

    val retainedBytes: Long
        get() = CACHE_OVERHEAD_BYTES + recordBytes + names.retainedBytes + generations.values.sumOf { it.metadataBytes }
    val retainedRows: Int get() = recordCount

    fun fields(obj: JsonObject): PerfNumericFields = PerfNumericFields(obj, names)

    fun read(path: Path, priority: Int, visit: PerfLineVisit, decode: PerfLineDecode) {
        val before = attributes(path)
        if (!before.regular) throw IOException("not a regular perf generation")
        FileChannel.open(path, READ).use { channel ->
            val opened = attributes(path)
            if (identity(path, before) != identity(path, opened)) {
                throw IOException("rotated while opening perf generation")
            }
            readState(channel, generation(path, opened, priority), opened, visit, decode)
        }
    }

    private fun readState(
        channel: FileChannel,
        state: PerfCachedGeneration,
        opened: PerfFileStamp,
        visit: PerfLineVisit,
        decode: PerfLineDecode,
    ) {
        val size = channel.size()
        // Filesystem stamps are hints, not proof: repairs can preserve both size and modification time.
        val unchangedPrefix = samePrefix(channel, state, size)
        val refresh = !unchangedPrefix || state.changed(size, opened.modified) || state.size > state.complete
        if (!unchangedPrefix) {
            reset(state)
            prefix.start(channel, 0L)
        }
        val previousTail = if (refresh) removeTail(state) else null
        retained(channel, state, visit)
        state.readLimit = size
        if (refresh) append(channel, state, previousTail, visit, decode)
        if (refresh) state.prefixDigest = prefix.fingerprint()
        state.size = size
        state.modified = opened.modified
        trim()
    }

    private fun retained(channel: FileChannel, state: PerfCachedGeneration, visit: PerfLineVisit) {
        val prefixEnd = state.lines.firstOrNull()?.start ?: state.complete
        val prefix = PerfLineReader(channel, 0L, prefixEnd)
        while (true) visit.raw(prefix.next() ?: break)
        state.lines.forEach { visit.kept(it.line) }
    }

    /** A same-inode repair may grow, shrink or overwrite; only unchanged completed bytes authorize append reuse. */
    private fun samePrefix(channel: FileChannel, state: PerfCachedGeneration, size: Long): Boolean {
        if (size < state.complete) return false
        prefix.start(channel, state.complete)
        val previous = state.prefixDigest ?: return state.complete == 0L
        return prefix.fingerprint().contentEquals(previous)
    }

    private fun attributes(path: Path): PerfFileStamp {
        val values = Files.readAttributes(path, "basic:fileKey,isRegularFile,creationTime,lastModifiedTime")
        return PerfFileStamp(
            key = values["fileKey"],
            regular = values["isRegularFile"] == true,
            created = values["creationTime"] as? FileTime ?: throw IOException("missing creation time"),
            modified = values["lastModifiedTime"] as? FileTime ?: throw IOException("missing modification time"),
        )
    }

    private fun identity(path: Path, attributes: PerfFileStamp): Any =
        attributes.key ?: (path.toAbsolutePath().normalize() to attributes.created)

    private fun generation(path: Path, attributes: PerfFileStamp, priority: Int): PerfCachedGeneration {
        val key = identity(path, attributes)
        generations[key]?.let {
            it.priority = priority
            it.path = path
            trim()
            return it
        }
        if (generations.size == CACHE_GENERATIONS) {
            val oldest = generations.minBy { it.value.priority }
            reset(oldest.value)
            generations.remove(oldest.key)
        }
        val state = PerfCachedGeneration(path, priority)
        if (CACHE_OVERHEAD_BYTES + state.metadataBytes + names.retainedBytes <= limitBytes) generations[key] = state
        trim()
        return state
    }

    private fun append(
        channel: FileChannel,
        state: PerfCachedGeneration,
        previousTail: PerfCachedEntry?,
        visit: PerfLineVisit,
        decode: PerfLineDecode,
    ) {
        val size = state.readLimit
        skipOptionalLf(channel, size, state)
        val reader = PerfLineReader(channel, state.complete, size)
        while (true) {
            val start = reader.position
            val raw = reader.next() ?: break
            val parsed = previousTail?.takeIf { start == it.start && raw == it.raw }?.line
                ?: decode.decode(raw)
            visit.kept(parsed)
            remember(state, PerfCachedEntry(start, parsed, raw.takeUnless { reader.terminated }))
            if (reader.terminated) {
                reader.hashTo(prefix)
                state.complete = reader.position
            }
            state.trailingCr = reader.trailingCr
        }
    }

    private fun skipOptionalLf(channel: FileChannel, size: Long, state: PerfCachedGeneration) {
        if (!state.trailingCr || state.complete >= size) return
        val next = ByteBuffer.allocate(1)
        channel.read(next, state.complete)
        if (next[0] == '\n'.code.toByte()) {
            prefix.write('\n'.code)
            state.complete++
        }
        state.trailingCr = false
    }

    private fun remember(state: PerfCachedGeneration, entry: PerfCachedEntry) {
        if (generations.values.none { it === state }) return
        state.lines.addLast(entry)
        recordBytes += entry.retainedBytes
        recordCount++
        trim()
    }

    private fun removeTail(state: PerfCachedGeneration): PerfCachedEntry? {
        val tail = state.lines.lastOrNull()?.takeIf { it.raw != null } ?: return null
        state.lines.removeLast()
        recordBytes -= tail.retainedBytes
        recordCount--
        return tail
    }

    private fun reset(state: PerfCachedGeneration) {
        state.lines.forEach { recordBytes -= it.retainedBytes }
        recordCount -= state.lines.size
        state.lines.clear()
        state.complete = 0L
        state.size = 0L
        state.trailingCr = false
        state.prefixDigest = null
    }

    private fun trim() {
        while (retainedBytes > limitBytes || recordCount > PERF_CACHE_ROWS) {
            val oldest = generations.values.filter { it.lines.isNotEmpty() }.minByOrNull { it.priority }
            if (oldest != null) {
                recordBytes -= oldest.lines.removeFirst().retainedBytes
                recordCount--
            } else {
                val state = generations.minByOrNull { it.value.priority } ?: break
                generations.remove(state.key)
            }
        }
    }
}

private data class PerfFileStamp(val key: Any?, val regular: Boolean, val created: FileTime, val modified: FileTime)

private class PerfCachedGeneration(var path: Path, var priority: Int) {
    // One charged node per entry; removing an entry releases its storage without retained capacity.
    val lines = LinkedList<PerfCachedEntry>()
    var complete = 0L
    var size = 0L
    var modified: FileTime? = null
    var trailingCr = false
    var readLimit = 0L
    var prefixDigest: ByteArray? = null
    val metadataBytes: Long get() = GENERATION_OVERHEAD_BYTES + path.toString().length * PATH_STORAGE_BYTES_PER_CHAR

    fun changed(nextSize: Long, nextModified: FileTime): Boolean = nextSize != size || nextModified != modified
}

private data class PerfCachedEntry(val start: Long, val line: PerfCachedLine, val raw: String?) {
    val retainedBytes: Long
        get() = line.retainedBytes + (raw?.let { PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES } ?: 0L)
}
