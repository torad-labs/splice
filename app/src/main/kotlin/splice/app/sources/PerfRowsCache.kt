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
            if (before.identity(path) != opened.identity(path)) {
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
        val unchangedPrefix = state.unchanged(size, opened.modified, opened.changed) || samePrefix(channel, state, size)
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
        state.changeTime = opened.changed
        trim()
    }

    private fun retained(channel: FileChannel, state: PerfCachedGeneration, visit: PerfLineVisit) {
        val selection = PerfCachedSelection(channel, visit, state.ranges)
        state.lines.forEach { selection.kept(it.start, it.end, it.line) }
        selection.gap(state.complete)
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
        // Kernel change time is not restored by setting mtime. Unsupported providers keep the byte proof.
        val changed = if ("unix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.getAttribute(path, "unix:ctime") as? FileTime
        } else {
            null
        }
        return PerfFileStamp(
            key = values["fileKey"],
            regular = values["isRegularFile"] == true,
            created = values["creationTime"] as? FileTime ?: throw IOException("missing creation time"),
            modified = values["lastModifiedTime"] as? FileTime ?: throw IOException("missing modification time"),
            changed = changed,
        )
    }

    private fun generation(path: Path, attributes: PerfFileStamp, priority: Int): PerfCachedGeneration {
        val key = attributes.identity(path)
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
            val hint = visit.beforeCutoff(raw)
            if (hint == null) {
                val parsed = previousTail?.takeIf { start == it.start && raw == it.raw }?.line
                    ?: decode.decode(raw)
                visit.kept(parsed)
                remember(state, PerfCachedEntry(start, parsed, raw.takeUnless { reader.terminated }, reader.position))
            } else {
                visit.raw(raw)
                if (reader.terminated) rememberRange(state, start, reader.position, hint, raw)
            }
            if (reader.terminated) {
                reader.hashTo(prefix)
                state.complete = reader.position
            }
            state.trailingCr = reader.trailingCr
        }
    }

    private fun rememberRange(state: PerfCachedGeneration, start: Long, end: Long, hint: Long, raw: String) {
        if (generations.values.none { it === state }) return
        val prior = state.ranges.lastOrNull()?.takeIf { it.end == start }
        val range = prior ?: PerfSkippedRange(start, end, hint).also {
            state.ranges.add(it)
            state.rangeBytes += it.retainedBytes
        }
        val beforeBytes = range.retainedBytes
        range.add(raw, hint, end)
        state.rangeBytes += range.retainedBytes - beforeBytes
        trim()
    }

    private fun skipOptionalLf(channel: FileChannel, size: Long, state: PerfCachedGeneration) {
        if (!state.trailingCr || state.complete >= size) return
        val next = ByteBuffer.allocate(1)
        channel.read(next, state.complete)
        if (next[0] == '\n'.code.toByte()) {
            prefix.write('\n'.code)
            state.lines.lastOrNull()?.takeIf { it.end == state.complete }?.let { it.end++ }
            state.ranges.lastOrNull()?.takeIf { it.end == state.complete }?.let { it.end++ }
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
        state.ranges.clear()
        state.rangeBytes = 0L
        state.changeTime = null
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

private data class PerfFileStamp(
    val key: Any?,
    val regular: Boolean,
    val created: FileTime,
    val modified: FileTime,
    val changed: FileTime?,
) {
    fun identity(path: Path): Any = key ?: (path.toAbsolutePath().normalize() to created)
}

private class PerfCachedGeneration(var path: Path, var priority: Int) {
    // One charged node per entry; removing an entry releases its storage without retained capacity.
    val lines = LinkedList<PerfCachedEntry>()
    val ranges = LinkedList<PerfSkippedRange>()
    var rangeBytes = 0L
    var changeTime: FileTime? = null
    var complete = 0L
    var size = 0L
    var modified: FileTime? = null
    var trailingCr = false
    var readLimit = 0L
    var prefixDigest: ByteArray? = null
    val metadataBytes: Long
        get() = GENERATION_OVERHEAD_BYTES + path.toString().length * PATH_STORAGE_BYTES_PER_CHAR +
            rangeBytes

    fun unchanged(nextSize: Long, nextModified: FileTime, changed: FileTime?): Boolean {
        if (changed == null || nextSize != complete) return false
        return !changed(nextSize, nextModified) && changeTime == changed
    }

    fun changed(nextSize: Long, nextModified: FileTime): Boolean = nextSize != size || nextModified != modified
}

private data class PerfCachedEntry(
    val start: Long,
    val line: PerfCachedLine,
    val raw: String?,
    var end: Long = start,
) {
    val retainedBytes: Long
        get() = line.retainedBytes + (raw?.let { PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES } ?: 0L)
}
