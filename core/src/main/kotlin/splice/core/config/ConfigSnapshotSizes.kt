package splice.core.config

import splice.core.memory.HeapJson
import splice.core.memory.HeapWeights

// Conservative container/entry overhead, shared with HeapJson's UTF-16 text estimate.
private const val CONFIG_CONTAINER_BYTES = 64L
private const val CONFIG_REFERENCE_BYTES = 16L

/** Construction-only, overlapping retained-graph estimates. No admission charge or content leaves the owner. */
internal object ConfigSnapshotSizes {
    fun normalized(values: Map<String, Any?>, chosen: Set<String>): Long = HeapJson.add(
        map(values),
        HeapJson.add(CONFIG_CONTAINER_BYTES, HeapWeights.multiply(chosen.size.toLong(), CONFIG_REFERENCE_BYTES)),
    )

    fun layers(layers: ConfigLayers): Long = listOf(
        layers.defaults,
        layers.headOverrides,
        layers.file,
        layers.env,
        layers.runtime,
        layers.perHead,
    ).fold(CONFIG_CONTAINER_BYTES) { total, layer -> HeapJson.add(total, map(layer)) }

    fun generation(inputs: ConfigSnapshotCache.Inputs, rejected: Map<String, String>, heads: Int): Long {
        val metadata = HeapJson.add(
            CONFIG_CONTAINER_BYTES,
            HeapWeights.multiply(heads.toLong(), CONFIG_REFERENCE_BYTES),
        )
        return listOf(inputs.file, inputs.env, inputs.runtime, inputs.heads, rejected)
            .fold(metadata) { total, layer -> HeapJson.add(total, map(layer)) }
    }

    private fun map(values: Map<*, *>): Long = values.entries.fold(CONFIG_CONTAINER_BYTES) { total, entry ->
        HeapJson.add(total, HeapJson.add(CONFIG_CONTAINER_BYTES, HeapJson.add(scalar(entry.key), scalar(entry.value))))
    }

    private fun <T> scalar(value: T): Long = when (value) {
        null -> 0L
        is String -> HeapJson.text(value)
        is Map<*, *> -> map(value)
        else -> CONFIG_REFERENCE_BYTES
    }
}
