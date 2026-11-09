package splice.observability.export

import io.opentelemetry.api.common.Attributes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfProjection
import splice.core.perf.observation.PerfScope

internal data class ExportMetric(
    val id: String,
    val name: String,
    val type: String,
    val unit: String,
    val factor: Double,
    val events: Set<String>,
)

/** Both wire formats consume this registry projection and the same converted population. */
internal object ExportSchema {
    private val registry = PerfMetricRegistry.schema(PerfProjection.EXPORT)["metrics"] as? JsonObject
    val metrics: Map<String, ExportMetric> = registry.orEmpty().mapNotNull { (id, value) ->
        val fields = value as? JsonObject ?: return@mapNotNull null
        val type = (fields["type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val unit = (fields["unit"] as? JsonPrimitive)?.content.orEmpty()
        val seconds = unit == "ms"
        val name = "splice_" + if (seconds) id.removeSuffix("_ms") + "_seconds" else id
        val events = (fields["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        id to ExportMetric(id, name, type, if (seconds) "s" else unit, if (seconds) MS_TO_SECONDS else 1.0, events)
    }.toMap()

    fun attributes(scope: PerfScope): Attributes = Attributes.builder().apply {
        put("head", scope.head)
        scope.model?.let { put("model", it) }
        scope.transport?.let { put("transport", it) }
        put("work", scope.work)
        scope.outcome?.let { put("outcome", it) }
        scope.streaming?.let { put("streaming", it) }
        scope.local?.let { put("local", it) }
        scope.compact?.let { put("compact", it) }
    }.build()

    fun scope(value: PerfScope): PerfScope = PerfScope(
        head = label(value.head) ?: UNKNOWN_EXPORT_DIMENSION,
        model = label(value.model),
        transport = value.transport?.takeIf { it in transports } ?: value.transport?.let { UNKNOWN_EXPORT_DIMENSION },
        work = value.work.takeIf { it in work } ?: UNKNOWN_EXPORT_DIMENSION,
        streaming = value.streaming,
        local = value.local,
        compact = value.compact,
        outcome = value.outcome?.let(::outcome),
    )

    fun outcome(value: String): String = when (value) {
        "ok", "good", "success" -> "good"
        "cancelled", "client_abort" -> "cancelled"
        "refused", "at_capacity" -> "refused"
        UNKNOWN_EXPORT_DIMENSION, "?" -> UNKNOWN_EXPORT_DIMENSION
        else -> "bad"
    }

    private fun label(value: String?): String? = value?.takeIf { label.matches(it) && !opaque.matches(it) }
    private val label = Regex("[A-Za-z0-9][A-Za-z0-9_./-]{0,95}")
    private val opaque = Regex("[0-9a-fA-F-]{24,}")
    private val transports = setOf("sse", "ws", "websocket", "http", "local", "mcp", UNKNOWN_EXPORT_DIMENSION)
    private val work = setOf("client", "compact", "tool", "compare", "local", "runtime", "instrumentation", "upstream")
}

// Prometheus and OTLP publish durations in seconds while the local metric registry records milliseconds.
private const val MS_TO_SECONDS = 0.001

private const val UNKNOWN_EXPORT_DIMENSION = "unknown"
