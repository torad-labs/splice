package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Operator telemetry is independent of product analytics. Null preserves config layer precedence. */
@Serializable
public data class TelemetryConfig(val operator: OperatorTelemetryConfig = OperatorTelemetryConfig())

@Serializable
public data class OperatorTelemetryConfig(
    @SerialName("prometheus_enabled") val prometheusEnabled: Boolean? = null,
    @SerialName("otlp_endpoint") val otlpEndpoint: String? = null,
    @SerialName("otlp_protocol") val otlpProtocol: String? = null,
    @SerialName("otlp_headers") val otlpHeaders: String? = null,
    @SerialName("otlp_signals") val otlpSignals: String? = null,
) {
    public fun findings(): List<TopologyFinding> = OperatorTelemetryRules.of(this)

    public fun knobs(): Map<String, String> = buildMap {
        prometheusEnabled?.let { put("telemetryPrometheusEnabled", it.toString()) }
        otlpEndpoint?.let { put("telemetryOtlpEndpoint", it) }
        otlpProtocol?.let { put("telemetryOtlpProtocol", it) }
        otlpHeaders?.let { put("telemetryOtlpHeaders", it) }
        otlpSignals?.let { put("telemetryOtlpSignals", it) }
    }

    override fun toString(): String = "OperatorTelemetryConfig([redacted])"
}
