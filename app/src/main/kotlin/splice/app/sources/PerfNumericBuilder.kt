// NEW: build compact numeric facts directly from streamed scalars without boxed map entries.
package splice.app.sources

// why: 32 primitive slots fit ordinary perf rows and grow once for the measured 40-field numeric shape.
private const val PERF_INITIAL_NUMERIC_FIELDS = 32

internal class PerfNumericBuilder(private val pool: PerfFieldNames) {
    val names = ArrayList<String>()
    var values = LongArray(PERF_INITIAL_NUMERIC_FIELDS)
        private set
    var unsharedNameBytes = 0L
        private set

    fun clear() {
        names.clear()
        unsharedNameBytes = 0L
    }

    fun put(key: String, value: Long) {
        if (names.size == values.size) values = values.copyOf(values.size * 2)
        names.add(pool.share(key))
        if (!pool.contains(key)) unsharedNameBytes += PERF_STRING_OVERHEAD_BYTES + key.length * PERF_CHAR_BYTES
        values[names.lastIndex] = value
    }
}
