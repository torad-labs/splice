package splice.app.sources.observability

import kotlinx.serialization.json.Json
import splice.core.perf.observation.PerfObservation
import splice.usage.perf.history.PerfEvidenceQuery

internal object PerfEvidenceCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): PerfObservation? = try {
        json.decodeFromString<PerfObservation>(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun matches(value: PerfObservation, query: PerfEvidenceQuery): Boolean {
        val inTime = value.stamp.wallMs >= query.fromMs && value.stamp.wallMs < query.untilMs
        val head = query.head == null || value.scope.head == query.head
        return inTime && head
    }

    fun identity(value: PerfObservation): String = "${value.stamp.identity.bootId}:${value.stamp.observationId}"

    fun charge(rawBytes: Long): Long = rawBytes * DECODE_EXPANSION + OBSERVATION_OVERHEAD
}

private const val DECODE_EXPANSION = 8L
private const val OBSERVATION_OVERHEAD = 2_048L
