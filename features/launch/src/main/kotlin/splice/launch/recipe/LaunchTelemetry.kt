// NEW: server mode (docs/specs/server-mode.md, F8, the laptop's half). The environment that sends a session's own
// telemetry to its team server's relay: telemetry on, metrics and logs exported over OTLP/HTTP to the relay, and nothing
// that logs a prompt. The person's key is in none of it: the session's otelHeadersHelper prints it (SessionHelpers).
// Claude Code sends that helper's headers to every exporter it runs, so an endpoint, protocol or header of a signal's
// own that the person's shell names, which would send the key somewhere else, is cleared from the launch, and so is
// OTEL_LOG_USER_PROMPTS. A launch with no relay plants none of this and clears nothing.
package splice.launch.recipe

import splice.client.TELEMETRY_SWITCH
import splice.launch.LaunchSpec

internal object LaunchTelemetry {
    /** What [spec]'s session is handed to export its telemetry to the relay; empty with no relay. */
    fun env(spec: LaunchSpec): Map<String, String> {
        val relay = spec.teamServer?.telemetry ?: return emptyMap()
        return mapOf(
            TELEMETRY_SWITCH to "1",
            "OTEL_METRICS_EXPORTER" to OTLP,
            "OTEL_LOGS_EXPORTER" to OTLP,
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/protobuf",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to relay.endpoint,
        )
    }

    /** What [spec]'s session must not inherit while its telemetry goes to the relay; nothing with no relay. */
    fun cleared(spec: LaunchSpec): List<String> {
        if (spec.teamServer?.telemetry == null) return emptyList()
        val perSignal = listOf("METRICS", "LOGS", "TRACES").flatMap { signal ->
            listOf("ENDPOINT", "PROTOCOL", "HEADERS").map { "OTEL_EXPORTER_OTLP_${signal}_$it" }
        }
        return listOf("OTEL_LOG_USER_PROMPTS", "OTEL_EXPORTER_OTLP_HEADERS") + perSignal
    }
}

private const val OTLP = "otlp"
