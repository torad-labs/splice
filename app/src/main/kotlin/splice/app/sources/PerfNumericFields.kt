// NEW: lossless packed primitive numeric perf facts, with bounded shared names and schemas.
package splice.app.sources

// Covers the numeric map, schema reference and byte-array header with conservative alignment.
private const val NUMERIC_ARRAY_OVERHEAD_BYTES = 64L

// Covers schema/name-key arrays, lookup indexes, map entry and aligned headers.
private const val NUMERIC_SCHEMA_OVERHEAD_BYTES = 160L

// why: 20 bytes cover each 8-byte name reference, 4-byte lookup index and aligned array capacity.
private const val NUMERIC_SCHEMA_FIELD_BYTES = 20L

// Covers String/backing headers, both name tables, collision nodes and capacity with 64-bit references.
private const val FIELD_NAME_OVERHEAD_BYTES = 320L

// A signed varint carries seven value bits per byte; the eighth bit announces another byte.
private const val PACKED_VALUE_BITS = 7
private const val PACKED_VALUE_MASK = (1 shl PACKED_VALUE_BITS) - 1
private const val PACKED_CONTINUATION = 1 shl PACKED_VALUE_BITS

/** All signed Long values and field presence remain exact; packing is private to the map contract. */
internal class PerfNumericFields(builder: PerfNumericBuilder) : AbstractMap<String, Long>() {
    private val schema = builder.schema()
    private val numbers = pack(builder)
    private val unsharedNameBytes = builder.unsharedNameBytes

    override val size: Int get() = schema.names.size
    override fun containsKey(key: String): Boolean = schema.index(key) >= 0

    /** Shared schemas are charged in the source pool, never once per row or left uncharged. */
    val retainedBytes: Long
        get() = NUMERIC_ARRAY_OVERHEAD_BYTES + numbers.size + unsharedNameBytes +
            if (schema.shared) 0L else schema.retainedBytes

    override fun get(key: String): Long? {
        val wanted = schema.index(key)
        if (wanted < 0) return null
        var position = 0
        var value = 0L
        repeat(wanted + 1) {
            value = 0L
            var shift = 0
            do {
                val next = numbers[position++].toInt() and UByte.MAX_VALUE.toInt()
                value = value or ((next and PACKED_VALUE_MASK).toLong() shl shift)
                shift += PACKED_VALUE_BITS
            } while (next and PACKED_CONTINUATION != 0)
        }
        return (value ushr 1) xor -(value and 1L)
    }

    override val entries: Set<Map.Entry<String, Long>>
        get() = object : AbstractSet<Map.Entry<String, Long>>() {
            override val size: Int get() = schema.names.size
            override fun iterator(): Iterator<Map.Entry<String, Long>> = object : Iterator<Map.Entry<String, Long>> {
                private var index = 0
                override fun hasNext(): Boolean = index < schema.names.size
                override fun next(): Map.Entry<String, Long> {
                    if (!hasNext()) throw NoSuchElementException()
                    val name = schema.names[index++]
                    return java.util.AbstractMap.SimpleImmutableEntry(name, requireNotNull(get(name)))
                }
            }
        }

    private fun pack(builder: PerfNumericBuilder): ByteArray {
        var size = 0
        for (index in builder.names.indices) {
            var value = (builder.values[index] shl 1) xor (builder.values[index] shr (Long.SIZE_BITS - 1))
            do {
                size++
                value = value ushr PACKED_VALUE_BITS
            } while (value != 0L)
        }
        val packed = ByteArray(size)
        var position = 0
        for (index in builder.names.indices) {
            var value = (builder.values[index] shl 1) xor (builder.values[index] shr (Long.SIZE_BITS - 1))
            do {
                val next = value and PACKED_VALUE_MASK.toLong()
                value = value ushr PACKED_VALUE_BITS
                packed[position++] = (next or if (value == 0L) 0L else PACKED_CONTINUATION.toLong()).toByte()
            } while (value != 0L)
        }
        return packed
    }
}

/** Field names and reusable ordered numeric schemas share one source-owned charged ceiling. */
internal class PerfFieldNames(private val limitBytes: Long) {
    private val names = HashMap<String, String>()
    private val schemas = HashMap<List<String>, PerfNumericSchema>()
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

    fun schema(fields: List<String>): PerfNumericSchema {
        schemas[fields]?.let { return it }
        val charge = NUMERIC_SCHEMA_OVERHEAD_BYTES + fields.size * NUMERIC_SCHEMA_FIELD_BYTES
        val shared = fields.all(names::containsKey) && retainedBytes + charge <= limitBytes
        val schema = PerfNumericSchema(fields.toList(), shared)
        if (shared) {
            schemas[schema.names] = schema
            retainedBytes += charge
        }
        return schema
    }
}

/** Immutable schema identity is an internal storage detail; callers still see an ordinary Map. */
internal class PerfNumericSchema(val names: List<String>, val shared: Boolean) {
    private val order = names.indices.sortedBy { names[it] }.toIntArray()
    val retainedBytes: Long get() = NUMERIC_SCHEMA_OVERHEAD_BYTES + names.size * NUMERIC_SCHEMA_FIELD_BYTES

    fun index(key: String): Int {
        var low = 0
        var high = order.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val index = order[middle]
            val comparison = names[index].compareTo(key)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return index
            }
        }
        return -1
    }
}
