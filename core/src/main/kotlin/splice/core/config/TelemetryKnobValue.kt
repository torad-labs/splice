package splice.core.config

import splice.core.perf.export.PerfExportValidation
import java.net.URI

/** Empty endpoint/signals are deliberate disabled values, unlike ordinary text knobs. */
internal object TelemetryKnobValue {
    val knobs: Set<Knob> = setOf(
        Knob.TELEMETRY_OTLP_ENDPOINT,
        Knob.TELEMETRY_OTLP_PROTOCOL,
        Knob.TELEMETRY_OTLP_HEADERS,
        Knob.TELEMETRY_OTLP_SIGNALS,
    )

    fun coerce(knob: Knob, raw: String?): String? {
        val text = raw?.trim() ?: return null
        val valid = when (knob) {
            Knob.TELEMETRY_OTLP_ENDPOINT -> PerfExportValidation.endpoint(text)
            Knob.TELEMETRY_OTLP_PROTOCOL -> PerfExportValidation.protocol(text)
            Knob.TELEMETRY_OTLP_HEADERS -> PerfExportValidation.parseHeaders(text) != null
            Knob.TELEMETRY_OTLP_SIGNALS -> PerfExportValidation.parseSignals(text) != null
            else -> false
        }
        return text.takeIf { valid }
    }

    fun rejectCombination(
        applied: MutableMap<String, Any?>,
        baseline: Map<String, Any?>,
    ): Map<String, String> {
        val changed = applied.keys.filter { it in ENDPOINT_KEYS }
        if (changed.isEmpty()) return emptyMap()
        val candidate = baseline + applied.mapValues { (key, value) -> value ?: knobsByKey[key]?.default }
        val endpoint = candidate[Knob.TELEMETRY_OTLP_ENDPOINT.key] as? String ?: ""
        val grpc = candidate[Knob.TELEMETRY_OTLP_PROTOCOL.key] == "grpc"
        val badPath = endpoint.isNotEmpty() && URI(endpoint).path.orEmpty() !in setOf("", "/")
        if (!grpc || !badPath) return emptyMap()
        return changed.associateWith {
            applied.remove(it)
            "gRPC telemetry endpoint cannot have a path"
        }
    }
}

private val ENDPOINT_KEYS = setOf(Knob.TELEMETRY_OTLP_ENDPOINT.key, Knob.TELEMETRY_OTLP_PROTOCOL.key)
