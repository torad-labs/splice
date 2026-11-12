package splice.core.config

import splice.core.perf.export.PerfExportValidation
import java.net.URI

/** Empty endpoint/signals are deliberate disabled values, unlike ordinary text knobs. */
internal object TelemetryKnobValue {
    private val validators: Map<Knob, TelemetryValueValidator> = mapOf(
        Knob.TELEMETRY_OTLP_ENDPOINT to TelemetryValueValidator(PerfExportValidation::endpoint),
        Knob.TELEMETRY_OTLP_PROTOCOL to TelemetryValueValidator(PerfExportValidation::protocol),
        Knob.TELEMETRY_OTLP_HEADERS to TelemetryValueValidator { PerfExportValidation.parseHeaders(it) != null },
        Knob.TELEMETRY_OTLP_SIGNALS to TelemetryValueValidator { PerfExportValidation.parseSignals(it) != null },
    )

    val knobs: Set<Knob> = validators.keys

    fun coerce(knob: Knob, raw: String?): String? {
        val text = raw?.trim() ?: return null
        return text.takeIf { validators[knob]?.accepts(it) == true }
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

    private fun interface TelemetryValueValidator {
        fun accepts(text: String): Boolean
    }
}

private val ENDPOINT_KEYS = setOf(Knob.TELEMETRY_OTLP_ENDPOINT.key, Knob.TELEMETRY_OTLP_PROTOCOL.key)
