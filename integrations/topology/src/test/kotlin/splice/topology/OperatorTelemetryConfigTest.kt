package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.restartRequiredKnobKeys
import splice.core.topology.TopologyKnobLayer
import splice.core.util.EnvReader
import java.nio.file.Path

class OperatorTelemetryConfigTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `operator namespace and collection alias reach global restart knobs`() {
        val source = """
            [defaults]
            perf_collection_enabled = "false"
            [telemetry.operator]
            prometheus_enabled = true
            otlp_endpoint = "http://127.0.0.1:4318"
            otlp_protocol = "http/protobuf"
            otlp_headers = '{"authorization":"Bearer header-secret"}'
            otlp_signals = "metrics,traces,logs"
        """.trimIndent()
        val read = ConfigFindings.read(source, directory)
        assertTrue(read is ConfigRead.Ready, read.toString())
        val topology = TopologyLoader.parse(source)
        val layer = TopologyKnobLayer(topology).configOverrides()
        val config = ConfigService(
            StatePaths(baseOverride = directory),
            layer,
            envReader = EnvReader { null },
        ).getConfig()
        assertFalse(config.perfCollectionEnabled)
        assertTrue(config.telemetryPrometheusEnabled)
        assertEquals(setOf("metrics", "traces", "logs"), config.perfExportConfig().signals)
        assertEquals("Bearer header-secret", config.perfExportConfig().headers["authorization"])
        assertTrue(layer.keys.all { it in restartRequiredKnobKeys })
    }

    @Test
    fun `malformed protocol endpoint headers and signals refuse with safe findings`() {
        val source = """
            [telemetry.operator]
            otlp_endpoint = "https://header-secret@example.test?q=header-secret"
            otlp_protocol = "ftp"
            otlp_headers = '{"authorization":["header-secret"]}'
            otlp_signals = "metrics,unknown"
        """.trimIndent()
        val read = ConfigFindings.read(source, directory)
        assertTrue(read is ConfigRead.Refused)
        val findings = (read as ConfigRead.Refused).findings
        assertEquals(4, findings.size)
        assertTrue(findings.all { it.path.startsWith("telemetry.operator.") })
        assertFalse(findings.toString().contains("header-secret"))
    }

    @Test
    fun `defaults keep collection local and endpoint or signals absence disables OTLP`() {
        val service = ConfigService(StatePaths(baseOverride = directory), envReader = EnvReader { null })
        assertTrue(service.getConfig().perfCollectionEnabled)
        assertFalse(service.getConfig().perfExportConfig().enabled)
        assertTrue(service.patch(mapOf("telemetryOtlpSignals" to "", "telemetryOtlpEndpoint" to "")).rejected.isEmpty())
        assertTrue(service.patch(mapOf("telemetryOtlpHeaders" to "[redacted]")).rejected.isNotEmpty())
        val conflict = service.patch(
            mapOf("telemetryOtlpProtocol" to "grpc", "telemetryOtlpEndpoint" to "http://example.test/path"),
        )
        assertEquals(setOf("telemetryOtlpProtocol", "telemetryOtlpEndpoint"), conflict.rejected.keys)
        assertFalse(service.getConfig().perfExportConfig().enabled)
    }
}
