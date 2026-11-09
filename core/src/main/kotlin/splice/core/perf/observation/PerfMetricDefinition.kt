package splice.core.perf.observation

import kotlinx.serialization.Serializable

@Serializable
public enum class PerfProjection { LOCAL, SUPPORT, EXPORT }

@Serializable
internal enum class PerfMetricType { COUNTER, HISTOGRAM, GAUGE, LEGACY }

@Serializable
internal data class PerfMetricDefinition(
    val id: String,
    val description: String,
    val type: PerfMetricType,
    val unit: String,
    val origin: String,
    val end: String,
    val population: String,
    val events: Set<PerfEventKind> = emptySet(),
    val projections: Set<PerfProjection> = PerfProjection.entries.toSet(),
    val missingReasons: List<String> = listOf("not_observed", "unsupported_path", "collection_disabled"),
    val paths: List<String> = listOf("streaming", "non_streaming", "local", "remote"),
)
