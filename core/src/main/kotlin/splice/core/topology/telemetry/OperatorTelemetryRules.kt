package splice.core.topology.telemetry

import splice.core.perf.export.PerfExportValidation
import splice.core.topology.TopologyFinding
import java.net.URI

/** One check serves topology PUT and boot findings; no diagnostic contains a supplied value. */
internal object OperatorTelemetryRules {
    fun of(operator: OperatorTelemetryConfig): List<TopologyFinding> = listOfNotNull(
        operator.otlpEndpoint?.takeUnless(PerfExportValidation::endpoint)?.let {
            finding("otlp_endpoint", "requires an HTTP(S) endpoint without credentials, query or fragment")
        },
        operator.otlpProtocol?.takeUnless(PerfExportValidation::protocol)?.let {
            finding("otlp_protocol", "requires http/protobuf or grpc")
        },
        operator.otlpHeaders?.takeIf { PerfExportValidation.parseHeaders(it) == null }?.let {
            finding("otlp_headers", "requires a bounded JSON object of valid header strings")
        },
        operator.otlpSignals?.takeIf { PerfExportValidation.parseSignals(it) == null }?.let {
            finding("otlp_signals", "requires comma-separated metrics,traces,logs or an empty string")
        },
        grpcPath(operator),
    )

    private fun grpcPath(operator: OperatorTelemetryConfig): TopologyFinding? {
        val endpoint = operator.otlpEndpoint.orEmpty()
        if (operator.otlpProtocol != "grpc" || endpoint.isEmpty()) return null
        if (!PerfExportValidation.endpoint(endpoint)) return null
        return if (URI(endpoint).path.orEmpty() !in setOf("", "/")) {
            finding("otlp_endpoint", "gRPC endpoint cannot have a signal path")
        } else {
            null
        }
    }

    private fun finding(key: String, message: String): TopologyFinding = TopologyFinding(
        "telemetry.operator.$key",
        message,
    )
}
