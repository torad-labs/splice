package splice.configuration.topology

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.knobs.ConfigRoutes
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.core.util.EnvReader
import splice.http.JsonBody
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class OperatorTelemetrySecretsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `config GET redacts effective values every layer and knob rows`() {
        val service = ConfigService(
            StatePaths(baseOverride = directory),
            headOverrides = mapOf("telemetryOtlpHeaders" to HEADERS),
            envReader = EnvReader { null },
        )
        val result = service.patch(mapOf("telemetryOtlpHeaders" to HEADERS))
        assertTrue(result.rejected.isEmpty())
        val output = ConfigRoutes(service, JsonBody()).configJson()
        assertFalse(output.contains(SECRET))
        assertTrue(output.contains("[redacted]"))
        assertEquals(HEADERS, service.getConfig().telemetryOtlpHeaders)
        assertTrue(service.patch(mapOf("telemetryOtlpHeaders" to "[redacted]")).rejected.isNotEmpty())
        assertEquals(HEADERS, service.getConfig().telemetryOtlpHeaders)
    }

    @Test
    fun `topology masked round trip preserves real secret and malformed edit refuses atomically`() {
        val file = directory.resolve("splice.toml")
        Files.writeString(file, "[telemetry.operator]\notlp_headers = '$HEADERS'\n")
        val writer = TopologyWriter(file, directory.resolve("backups"), TopologyParse(TopologyLoader::parse))
        val routes = TopologyRoutes(TopologyWriterSource { writer }, TopologyStale { false })
        val read = routes.read().body
        assertFalse(read.contains(SECRET))
        val tree = Json.parseToJsonElement(read).jsonObject.getValue("topology").jsonObject
        val payload = JsonObject(mapOf("topology" to tree)).toString()
        val written = Json.parseToJsonElement(routes.write(payload).body).jsonObject
        assertTrue(written.getValue("ok").jsonPrimitive.boolean, written.toString())
        assertEquals(HEADERS, TopologyLoader.parse(Files.readString(file)).telemetry.operator.otlpHeaders)
        val telemetry = tree.getValue("telemetry").jsonObject
        val operator = telemetry.getValue("operator").jsonObject
        val invalid = JsonObject(operator + ("otlp_headers" to JsonPrimitive("$SECRET invalid-json")))
        val updated = JsonObject(tree + ("telemetry" to JsonObject(telemetry + ("operator" to invalid))))
        val before = Files.readString(file)
        val refused = routes.write(JsonObject(mapOf("topology" to updated)).toString()).body
        assertFalse(Json.parseToJsonElement(refused).jsonObject.getValue("ok").jsonPrimitive.boolean)
        assertFalse(refused.contains(SECRET))
        assertEquals(before, Files.readString(file))
    }
}

private const val SECRET = "header-secret-fixture"
private const val HEADERS = "{\"authorization\":\"Bearer $SECRET\"}"
