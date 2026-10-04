// NEW: compact primitive-array numeric perf facts, with a bounded field-name pool.
package splice.app.sources

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Charges cover array headers, field references/values/indexes, and the shared map entry per key.
private const val NUMERIC_ARRAY_OVERHEAD_BYTES = 160L

// Each field retains an 8-byte reference, an 8-byte value and a 4-byte lookup index.
private const val NUMERIC_FIELD_BYTES = 20L

// Covers String/backing headers, worst-case collision tree nodes and table growth with 64-bit references.
private const val FIELD_NAME_OVERHEAD_BYTES = 256L

/** Numeric facts retain primitive arrays, not a JSON tree or one boxed Long and map node per field. */
internal class PerfNumericFields(obj: JsonObject, pool: PerfFieldNames) : AbstractMap<String, Long>() {
    private val names: Array<String>
    private val numbers: LongArray
    private val order: IntArray
    private val unsharedNameBytes: Long

    init {
        val keys = ArrayList<String>()
        val values = LongArray(obj.size)
        var extra = 0L
        obj.forEach { (key, value) ->
            val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (number != null) {
                keys += pool.share(key)
                if (!pool.contains(key)) extra += PERF_STRING_OVERHEAD_BYTES + key.length * PERF_CHAR_BYTES
                values[keys.lastIndex] = number
            }
        }
        names = keys.toTypedArray()
        order = names.indices.sortedBy { names[it] }.toIntArray()
        numbers = values.copyOf(names.size)
        unsharedNameBytes = extra
    }

    override val size: Int get() = numbers.size

    /** Conservatively charged array headers, references, primitive values and unpooled field names. */
    val retainedBytes: Long get() = NUMERIC_ARRAY_OVERHEAD_BYTES + size * NUMERIC_FIELD_BYTES + unsharedNameBytes

    override fun get(key: String): Long? {
        var low = 0
        var high = order.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val index = order[middle]
            val comparison = names[index].compareTo(key)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return numbers[index]
            }
        }
        return null
    }

    override val entries: Set<Map.Entry<String, Long>>
        get() = object : AbstractSet<Map.Entry<String, Long>>() {
            override val size: Int get() = numbers.size
            override fun iterator(): Iterator<Map.Entry<String, Long>> = object : Iterator<Map.Entry<String, Long>> {
                private var index = 0
                override fun hasNext(): Boolean = index < numbers.size
                override fun next(): Map.Entry<String, Long> {
                    if (!hasNext()) throw NoSuchElementException()
                    val at = index++
                    return java.util.AbstractMap.SimpleImmutableEntry(names[at], numbers[at])
                }
            }
        }
}

/** Field names are shared only within this source, under a separate charged ceiling. */
internal class PerfFieldNames(private val limitBytes: Long) {
    private val names = HashMap<String, String>()
    var retainedBytes: Long = 0L
        private set

    fun contains(key: String): Boolean = names.containsKey(key)

    fun share(key: String): String {
        names[key]?.let { return it }
        val charge = FIELD_NAME_OVERHEAD_BYTES + key.length * PERF_CHAR_BYTES
        if (retainedBytes + charge <= limitBytes) {
            names[key] = key
            retainedBytes += charge
        }
        return key
    }
}
