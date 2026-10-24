// NEW: bounded append-aware compact perf retention, with no second on-disk format.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import splice.app.sources.dense.PerfCachedEntry
import splice.app.sources.dense.PerfDenseCodec
import splice.app.sources.dense.PerfDenseEntries
import splice.app.sources.dense.PerfEntryFactory
import splice.app.sources.dense.PerfLinkedEntries
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

/** Per-source byte ceiling: the synthetic 27,537-row daily shape fits without retaining all archives. */
internal const val PERF_CACHE_BYTES: Long = 64L * 1_024 * 1_024

// Even tiny or invalid entries cannot outnumber what the single byte ceiling can charge.
internal const val PERF_CACHE_ROWS: Int = (PERF_CACHE_BYTES / PERF_RECORD_OVERHEAD_BYTES).toInt()

// The whole rows the work and economics readers share: the newest requests only, so the source stays near its old ceiling.
internal const val PERF_FULL_TIER_BYTES: Long = 16L * 1_024 * 1_024

// The compact rows of the turns route: a week of the busiest command (about 240,000 requests) fits.
internal const val PERF_COMPACT_BYTES: Long = 64L * 1_024 * 1_024

// Unknown field names cannot retain more than 1 MiB of shared names per source.
private const val FIELD_NAME_BYTES = 1_024L * 1_024

// Bounds identities and metadata independently of the number of archived generations on disk.
private const val CACHE_GENERATIONS = 64

// Small custom caches reserve at most one sixteenth of their ceiling for shared field names.
private const val FIELD_NAME_BUDGET_DIVISOR = 16L

/** A bounded, append-aware recent suffix. Older evicted prefixes keep the original on-demand path.
 *  The byte charge includes compact records, field-name sharing, generation metadata and a pending
 *  last line. Returned windows and one input-line decode are transient/caller memory, as before. */
internal class PerfRowsCache(
    private val limitBytes: Long = PERF_CACHE_BYTES,
    private val keep: PerfLineKeep = PerfTurnsRetention(),
    private val limitRows: Int = (limitBytes / PERF_RECORD_OVERHEAD_BYTES).toInt(),
    /** The numeric fields a row keeps, in order; null keeps them all. */
    numeric: List<String>? = null,
    /** Whether lines are retained as dense records (needs [numeric]) instead of objects, and read back by spot. */
    dense: Boolean = false,
) {
    init {
        require(limitBytes >= CACHE_OVERHEAD_BYTES)
    }

    private val names = PerfFieldNames(minOf(limitBytes / FIELD_NAME_BUDGET_DIVISOR, FIELD_NAME_BYTES), numeric)
    private val generations = LinkedHashMap<Any, PerfCachedGeneration>()
    private val codec = if (dense) PerfDenseCodec(names, requireNotNull(numeric)) else null
    private val entries = PerfEntryFactory { ref -> codec?.let { PerfDenseEntries(it, ref) } ?: PerfLinkedEntries() }
    private val prefix = PerfPrefixDigest()
    internal val decoder = PerfRowDecode(names)
    private val observation = PerfCacheFootprint.observe(this)

    /** The oldest cutoff any read of this cache has asked for; a wider one starts the cache over. */
    internal var since: Long? = null

    private val recordBytes: Long get() = generations.values.sumOf { it.lines.bytes }
    private val recordCount: Int get() = generations.values.sumOf { it.lines.size }

    val retainedBytes: Long
        get() = CACHE_OVERHEAD_BYTES + recordBytes + names.retainedBytes + (codec?.retainedBytes ?: 0L) +
            generations.values.sumOf { it.metadataBytes }

    fun versions(): List<PerfReadVersion> = generations.values.map { state ->
        PerfReadVersion(state.path, state.version, state.receipt, state.complete, state.prefixDigest)
    }

    fun clear() {
        generations.values.forEach(::reset)
        generations.clear()
        observation.resize(retainedBytes)
    }

    fun fields(obj: JsonObject): PerfNumericFields {
        val builder = PerfNumericBuilder(names)
        obj.forEach { (key, value) ->
            val number = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (number != null) builder.put(key, number)
        }
        return builder.fields()
    }

    fun read(path: Path, priority: Int, visit: PerfLineVisit, decode: PerfLineDecode) {
        val before = PerfFileStamp(path)
        if (!before.regular) throw IOException("not a regular perf generation")
        FileChannel.open(path, READ).use { channel ->
            val opened = PerfFileStamp(path)
            if (before.identity(path) != opened.identity(path)) {
                throw IOException("rotated while opening perf generation")
            }
            try {
                readState(channel, generation(path, opened, priority), opened, visit, decode)
            } finally {
                // Existing bounded metadata totals, never another scan of row payloads.
                observation.resize(retainedBytes)
            }
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
        for (entry in state.lines) selection.kept(entry.start, entry.end, entry.line)
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
        val state = PerfCachedGeneration(path, priority, entries)
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
                val retained = keep.keep(parsed, names).placed(state.ref, start, reader.position)
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
            state.lines.extendLast(state.complete)
            state.ranges.lastOrNull()?.takeIf { it.end == state.complete }?.let { it.end++ }
            state.complete++
        }
        state.trailingCr = false
    }

    private fun remember(state: PerfCachedGeneration, entry: PerfCachedEntry) {
        if (generations.values.none { it === state }) return
        state.lines.addLast(entry)
        trim()
    }

    private fun removeTail(state: PerfCachedGeneration): PerfCachedEntry? {
        val tail = state.lines.lastOrNull()?.takeIf { it.raw != null } ?: return null
        state.lines.removeLast()
        return tail
    }

    private fun reset(state: PerfCachedGeneration) {
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
            val oldest = generations.values.filter { !it.lines.isEmpty() }.minByOrNull { it.priority }
            if (oldest != null) {
                oldest.evicted(oldest.lines.removeFirst())
            } else {
                val state = generations.minByOrNull { it.value.priority } ?: break
                generations.remove(state.key)
            }
        }
    }
}
