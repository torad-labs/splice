// NEW: bounded append-aware compact perf retention, with no second on-disk format.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import splice.core.util.JsonlAppendProof
import splice.core.util.JsonlAppendReceipt
import splice.core.util.JsonlFileVersion
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.FileTime
import java.util.LinkedList

/** Per-source byte ceiling: the synthetic 27,537-row daily shape fits without retaining all archives. */
internal const val PERF_CACHE_BYTES: Long = 64L * 1_024 * 1_024

// Even tiny or invalid entries cannot outnumber what the single byte ceiling can charge.
internal const val PERF_CACHE_ROWS: Int = (PERF_CACHE_BYTES / PERF_RECORD_OVERHEAD_BYTES).toInt()

// Unknown field names cannot retain more than 1 MiB of shared names per source.
private const val FIELD_NAME_BYTES = 1_024L * 1_024

// Bounds identities and metadata independently of the number of archived generations on disk.
private const val CACHE_GENERATIONS = 64

// Small custom caches reserve at most one sixteenth of their ceiling for shared field names.
private const val FIELD_NAME_BUDGET_DIVISOR = 16L

// Covers generation, identity, linked-list and map objects, digest and amortized map capacity.
private const val GENERATION_OVERHEAD_BYTES = 1_024L

// Covers the 8 KiB digest buffer, bounded parser recycler and scratch, and cache objects with headroom.
private const val CACHE_OVERHEAD_BYTES = 24L * 1_024

// Covers the path's byte/string forms and fallback normalized identity with worst-case UTF-16.
private const val PATH_STORAGE_BYTES_PER_CHAR = 8L

/** A bounded, append-aware recent suffix. Older evicted prefixes keep the original on-demand path.
 *  The byte charge includes compact records, field-name sharing, generation metadata and a pending
 *  last line. Returned windows and one input-line decode are transient/caller memory, as before. */
internal class PerfRowsCache(
    private val limitBytes: Long = PERF_CACHE_BYTES,
    private val keep: PerfLineKeep = PerfTurnsRetention(),
    private val limitRows: Int = (limitBytes / PERF_RECORD_OVERHEAD_BYTES).toInt(),
) {
    init {
        require(limitBytes >= CACHE_OVERHEAD_BYTES)
    }

    private val names = PerfFieldNames(minOf(limitBytes / FIELD_NAME_BUDGET_DIVISOR, FIELD_NAME_BYTES))
    private val generations = LinkedHashMap<Any, PerfCachedGeneration>()
    private var recordBytes = 0L
    private var recordCount = 0
    private val prefix = PerfPrefixDigest()
    internal val decoder = PerfRowDecode(names)

    val retainedBytes: Long
        get() = CACHE_OVERHEAD_BYTES + recordBytes + names.retainedBytes + generations.values.sumOf { it.metadataBytes }
    val retainedRows: Int get() = recordCount

    fun versions(): List<PerfReadVersion> = generations.values.map { state ->
        PerfReadVersion(state.path, state.version, state.receipt, state.complete, state.prefixDigest)
    }

    fun clear() {
        generations.values.forEach(::reset)
        generations.clear()
    }

    fun fields(obj: JsonObject): PerfNumericFields {
        val builder = PerfNumericBuilder(names)
        obj.forEach { (key, value) ->
            val number = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (number != null) builder.put(key, number)
        }
        return PerfNumericFields(builder)
    }

    fun read(path: Path, priority: Int, visit: PerfLineVisit, decode: PerfLineDecode) {
        val before = PerfFileStamp(path)
        if (!before.regular) throw IOException("not a regular perf generation")
        FileChannel.open(path, READ).use { channel ->
            val opened = PerfFileStamp(path)
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
        // Only the product writer's exact stamp chain authorizes growth without rehashing old bytes.
        val receipt = opened.receipt
        val appendState = state.appendProof(opened)
        appendState?.let(prefix::resume)
        // External repairs still use the original complete-prefix byte proof.
        val unchangedPrefix = appendState != null || state.unchanged(size, opened.modified, opened.changed) ||
            prefix.matches(channel, size, state.complete, state.prefixDigest)
        val refresh = !unchangedPrefix || state.changed(size, opened.modified) || state.size > state.complete
        if (!unchangedPrefix) {
            reset(state)
            prefix.start(channel, 0L)
        }
        val previousTail = if (refresh) removeTail(state) else null
        retained(channel, state, visit)
        state.readLimit = size
        if (refresh) append(channel, state, previousTail, visit, decode)
        if (refresh) {
            state.prefixDigest = prefix.fingerprint()
            state.prefixState = prefix.save()
        }
        state.size = size
        state.modified = opened.modified
        state.changeTime = opened.changed
        state.version = opened.version.takeIf { it.size == size }
        state.receipt = receipt
        trim()
    }

    private fun retained(channel: FileChannel, state: PerfCachedGeneration, visit: PerfLineVisit) {
        val selection = PerfCachedSelection(channel, visit, state.ranges)
        state.lines.forEach { selection.kept(it.start, it.end, it.line) }
        selection.gap(state.complete)
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
                val retained = keep.keep(parsed, names)
                visit.kept(retained)
                remember(state, PerfCachedEntry(start, retained, raw.takeUnless { reader.terminated }, reader.position))
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
        state.prefixState = null
        state.version = null
        state.receipt = null
    }

    private fun trim() {
        while (retainedBytes > limitBytes || recordCount > limitRows) {
            val oldest = generations.values.filter { it.lines.isNotEmpty() }.minByOrNull { it.priority }
            if (oldest != null) {
                val evicted = oldest.lines.removeFirst()
                recordBytes -= evicted.retainedBytes
                recordCount--
                oldest.evicted(evicted)
            } else {
                val state = generations.minByOrNull { it.value.priority } ?: break
                generations.remove(state.key)
            }
        }
    }
}

internal data class PerfReadVersion(
    val path: Path,
    val version: JsonlFileVersion?,
    val receipt: JsonlAppendReceipt?,
    val complete: Long,
    val digest: ByteArray?,
) {
    fun coherent(): Boolean {
        val before = version ?: return false
        return try {
            val after = JsonlAppendProof.version(path)
            when {
                after.changed != null && before == after -> true
                JsonlAppendProof.current(path)?.continues(before, after, receipt) == true -> true
                after.changed == null -> FileChannel.open(path, READ).use { channel ->
                    PerfPrefixDigest().matches(channel, after.size, complete, digest)
                }
                else -> false
            }
        } catch (_: IOException) {
            false
        }
    }
}

private class PerfFileStamp(path: Path) {
    val version: JsonlFileVersion = JsonlAppendProof.version(path)
    val receipt: JsonlAppendReceipt? = JsonlAppendProof.current(path)
    val regular: Boolean get() = version.regular
    val modified: FileTime get() = version.modified
    val changed: FileTime? get() = version.changed
    fun identity(path: Path): Any = version.key ?: (path.toAbsolutePath().normalize() to version.created)
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
    var prefixState: PerfPrefixState? = null
    var version: JsonlFileVersion? = null
    var receipt: JsonlAppendReceipt? = null
    val metadataBytes: Long
        get() = GENERATION_OVERHEAD_BYTES + path.toString().length * PATH_STORAGE_BYTES_PER_CHAR +
            rangeBytes

    fun evicted(entry: PerfCachedEntry) {
        val row = entry.line.row ?: return
        if (entry.line.probe || entry.raw != null) return
        val previous = ranges.lastOrNull { it.end == entry.start }
        val range = previous ?: PerfSkippedRange(entry.start, entry.end, row.ts).also {
            val position = ranges.indexOfFirst { next -> next.start > it.start }
                .takeIf { index -> index >= 0 } ?: ranges.size
            ranges.add(position, it)
            rangeBytes += it.retainedBytes
        }
        val before = range.retainedBytes
        range.add(entry.line, entry.end)
        rangeBytes += range.retainedBytes - before
    }

    fun appendProof(opened: PerfFileStamp): PerfPrefixState? = prefixState?.takeIf {
        version?.let { before -> opened.receipt?.continues(before, opened.version, receipt) } == true
    }

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
