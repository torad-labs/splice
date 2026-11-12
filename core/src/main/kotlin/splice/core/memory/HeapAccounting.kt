package splice.core.memory

/** Admission-backed allocations and overlapping retained-owner estimates never share their gauges. */
internal enum class HeapAccounting {
    CHARGED,
    OBSERVED,
    ;

    fun index(category: HeapCategory): Int = ordinal * HeapCategory.entries.size + category.ordinal
}
