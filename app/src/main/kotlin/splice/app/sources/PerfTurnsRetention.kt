// NEW: byte-bounded usage facts and a bounded full-row display cache, without a second disk format.
package splice.app.sources

import splice.core.perf.PerfKeys
import splice.core.util.JsonlAppendProof
import splice.usage.perf.PerfRow
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ

// Full display rows reserve part of the same projected-read ceiling, never an uncharged second cache.
internal const val PERF_DISPLAY_BYTES = 8L * 1_024 * 1_024

// Covers the numeric wrapper, offset locator and linked-map entry with conservative alignment.
private const val PROJECTED_LOCATION_BYTES = 128L

/** Only fields used by filter, token-presence accounting and the shared per-row billing contract. */
private val usageFields = listOf(
    PerfKeys.IN_TOKENS, PerfKeys.OUT_TOKENS, PerfKeys.CACHED_TOKENS, PerfKeys.CACHE_WRITE_TOKENS,
    PerfKeys.ABSORBED_ROUNDS, PerfKeys.ABSORBED_IN_TOKENS, PerfKeys.ABSORBED_CACHED_TOKENS,
    PerfKeys.ABSORBED_CACHE_WRITE_TOKENS, PerfKeys.ABSORBED_OUT_TOKENS, PerfKeys.LOCAL_STEP,
)

/** The projected source retains no whole-window trace ids, arbitrary numeric bags or input strings. */
internal class PerfTurnsRetention : PerfLineKeep {
    private val complete = LinkedHashMap<PerfRowLocation, Complete>()
    var retainedBytes = 0L
        private set

    override fun keep(location: PerfRowLocation, line: PerfCachedLine, names: PerfFieldNames): PerfCachedLine {
        val row = line.row ?: return line
        if (row.fields is PerfProjectedFields) return line
        remember(location, row, line.retainedBytes)
        var textBytes = 0L
        fun share(value: String?): String? = value?.let {
            val shared = names.share(it)
            if (!names.contains(it)) textBytes += PERF_STRING_OVERHEAD_BYTES + it.length * PERF_CHAR_BYTES
            shared
        }
        val numeric = PerfProjectedFields(row.fields, location)
        val facts = row.copy(
            fields = numeric,
            outcome = requireNotNull(share(row.outcome)),
            model = share(row.model),
            session = share(row.session),
            account = share(row.account),
            sessionId = share(row.sessionId),
            turn = null,
            responseMessageId = null,
        )
        return line.copy(
            row = facts,
            numericBytes = PROJECTED_LOCATION_BYTES + usageFields.size * Long.SIZE_BYTES,
            retainedTextBytes = textBytes,
        )
    }

    fun complete(row: PerfRow, decode: PerfLineDecode): PerfRow {
        val projected = row.fields as? PerfProjectedFields ?: return row
        val location = projected.location
        complete[location]?.let { return it.row }
        if (!readablePrefix(location.generation)) throw PerfProjectionChanged()
        val line = load(location, decode)
        if (!readablePrefix(location.generation)) throw PerfProjectionChanged()
        val full = requireNotNull(line.row) { "a selected perf row no longer decodes" }
        remember(location, full, line.retainedBytes)
        return full
    }

    private fun load(location: PerfRowLocation, decode: PerfLineDecode): PerfCachedLine =
        FileChannel.open(location.generation.path, READ).use { channel ->
            val reader = PerfLineReader(channel, location.start, location.end)
            decode.decode(reader.next() ?: throw PerfProjectionChanged())
        }

    /** Deferred display bytes must still belong to the aggregate snapshot. An external edit retries
     * the complete read and render; unsupported stamps retain the original byte-prefix proof. */
    private fun readablePrefix(generation: PerfGenerationPath): Boolean {
        val before = generation.version ?: return false
        return try {
            val after = JsonlAppendProof.version(generation.path)
            when {
                after.changed != null && before == after -> true
                JsonlAppendProof.current(generation.path)?.continues(before, after, generation.receipt) == true -> true
                after.changed == null -> FileChannel.open(generation.path, READ).use { channel ->
                    PerfPrefixDigest().matches(channel, after.size, generation.complete, generation.prefixDigest)
                }
                else -> false
            }
        } catch (_: IOException) {
            false // The retry's ordinary scan reports absent/unreadable generations through its existing evidence.
        }
    }

    fun clear() {
        complete.clear()
        retainedBytes = 0L
    }

    private fun remember(location: PerfRowLocation, row: PerfRow, bytes: Long) {
        complete.remove(location)?.let { retainedBytes -= it.bytes }
        val charge = bytes + PROJECTED_LOCATION_BYTES
        if (charge > PERF_DISPLAY_BYTES) return
        complete[location] = Complete(row, charge)
        retainedBytes += charge
        while (retainedBytes > PERF_DISPLAY_BYTES) {
            val first = complete.entries.iterator()
            val entry = first.next()
            retainedBytes -= entry.value.bytes
            first.remove()
        }
    }

    private data class Complete(val row: PerfRow, val bytes: Long)
}

/** Fixed primitive slots preserve absence independently of zero, without per-row names or lookup arrays. */
private class PerfProjectedFields(
    fields: Map<String, Long>,
    val location: PerfRowLocation,
) : AbstractMap<String, Long>() {
    private val numbers = LongArray(usageFields.size)
    private var present = 0

    init {
        usageFields.forEachIndexed { index, key ->
            fields[key]?.let {
                numbers[index] = it
                present = present or (1 shl index)
            }
        }
    }

    override val size: Int get() = Integer.bitCount(present)
    override fun containsKey(key: String): Boolean = get(key) != null

    override fun get(key: String): Long? {
        val index = usageFields.indexOf(key)
        return if (index < 0 || present and (1 shl index) == 0) null else numbers[index]
    }

    override val entries: Set<Map.Entry<String, Long>>
        get() = usageFields.mapNotNullTo(linkedSetOf()) { key ->
            get(key)?.let { java.util.AbstractMap.SimpleImmutableEntry(key, it) }
        }
}
