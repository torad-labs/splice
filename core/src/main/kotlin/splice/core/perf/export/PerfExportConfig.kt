package splice.core.perf.export

/** Operator-owned external telemetry. No endpoint or no signals means no network activity. */
public data class PerfExportConfig(
    val prometheus: Boolean = false,
    val endpoint: String? = null,
    val protocol: String = "http/protobuf",
    val headers: Map<String, String> = emptyMap(),
    val signals: Set<String> = emptySet(),
) {
    init {
        require(PerfExportValidation.protocol(protocol)) { "Invalid telemetry protocol" }
        require(PerfExportValidation.endpoint(endpoint.orEmpty())) { "Invalid telemetry endpoint" }
        require(PerfExportValidation.headers(headers)) { "Invalid telemetry headers" }
        require(signals.all { it in PerfExportValidation.signals }) { "Invalid telemetry signals" }
        require(
            protocol != "grpc" || endpoint.isNullOrEmpty() || java.net.URI(endpoint).path.orEmpty() in setOf("", "/"),
        ) {
            "gRPC telemetry endpoint cannot have a path"
        }
    }

    public val enabled: Boolean get() = !endpoint.isNullOrBlank() && signals.isNotEmpty()

    override fun toString(): String =
        "PerfExportConfig(prometheus=$prometheus, endpointConfigured=${!endpoint.isNullOrEmpty()}, " +
            "protocol=$protocol, headers=[redacted], signals=$signals)"
}
