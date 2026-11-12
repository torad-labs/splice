package splice.app.instrumentation

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ConsolePorts
import splice.app.control.ControlAuth
import splice.app.control.perf.PerfMount
import splice.app.observability.PerfTelemetry
import splice.app.sources.PerfSourceFiles
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.PerfWorkBudget
import splice.usage.alerts.AlertStore
import java.nio.file.Path

class PerfAssemblyRoutesTest {
    @Test
    fun `new routes require the existing bearer before validation or file work`(@TempDir directory: Path) {
        val paths = StatePaths(baseOverride = directory)
        val key = MgmtKey(paths)
        val guard = ControlAuth(key, LogSink {}).guard()
        testApplication {
            application { routing { PerfMount(ConsolePorts(), guard).register(this) } }
            listOf(
                "schema", "series", "diagnose", "observations", "runtime", "metrics",
                "profiles", "profiles/capabilities", "instrumentation/status",
                "profiles/bad", "profiles/bad/artifact", "objectives", "alerts", "alerts/status", "export/status",
            ).forEach {
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/perf/$it").status, it)
            }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/perf/profiles") { setBody("bad") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/perf/profiles/bad/stop").status)
            assertEquals(HttpStatusCode.Unauthorized, client.put("/api/perf/objectives") { setBody("bad") }.status)
        }
    }

    @Test
    fun `wired capabilities and settings answer actual components and malformed captures fail`(
        @TempDir directory: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = directory)
        val config = ConfigService(paths, envReader = EnvReader { null })
        val telemetry =
            PerfTelemetry(
                config,
                setOf("one"),
                PerfSourceFiles(paths),
                AlertStore(directory.resolve("alerts.json")),
                LogSink {},
            )
        val key = MgmtKey(paths)
        val bearer = key.get()
        val ports = ConsolePorts().apply { performance = telemetry }
        val guard = ControlAuth(key, LogSink {}).guard()
        try {
            telemetry.start()
            testApplication {
                application { routing { PerfMount(ports, guard).register(this) } }
                val capabilities = client.get("/api/perf/profiles/capabilities") { bearerAuth(bearer) }
                assertEquals(HttpStatusCode.OK, capabilities.status)
                assertTrue("capabilities" in Json.parseToJsonElement(capabilities.bodyAsText()).jsonObject)
                val settings = client.get("/api/perf/objectives") { bearerAuth(bearer) }
                assertEquals(HttpStatusCode.OK, settings.status)
                assertTrue("revision" in Json.parseToJsonElement(settings.bodyAsText()).jsonObject)
                assertInstrumentation(client, bearer, telemetry)
                assertFileIoRuntime(client, bearer, telemetry)
                val scrape = client.get("/api/perf/metrics") { bearerAuth(bearer) }
                assertEquals(HttpStatusCode.NotFound, scrape.status)
                val malformed = client.post("/api/perf/profiles") {
                    bearerAuth(bearer)
                    setBody("{\"durationSeconds\":99999}")
                }
                assertEquals(HttpStatusCode.BadRequest, malformed.status)
                assertEquals(
                    "error",
                    JsonScalars.str(Json.parseToJsonElement(malformed.bodyAsText()).jsonObject, "status"),
                )
            }
        } finally {
            telemetry.close()
        }
    }

    private suspend fun assertInstrumentation(client: HttpClient, bearer: String, telemetry: PerfTelemetry) {
        val ownership = client.get("/api/perf/instrumentation/status") { bearerAuth(bearer) }
        assertEquals(HttpStatusCode.OK, ownership.status)
        assertEquals(ContentType.Application.Json, ownership.contentType()?.withoutParameters())
        val diagnostic = Json.parseToJsonElement(ownership.bodyAsText()).jsonObject
        assertEquals(telemetry.identity.build, JsonScalars.str(diagnostic["identity"]!!.jsonObject, "build"))
        assertEquals("local", JsonScalars.str(diagnostic, "collection_mode"))
        val work = diagnostic["memory"]!!.jsonObject["work"]!!.jsonObject
        assertEquals(PerfWorkBudget.LIMIT_BYTES.toString(), JsonScalars.str(work, "limit_bytes"))
        assertTrue("sources" in diagnostic)
    }

    private suspend fun assertFileIoRuntime(client: HttpClient, bearer: String, telemetry: PerfTelemetry) {
        telemetry.runtime.sample()
        val response = client.get("/api/perf/runtime") { bearerAuth(bearer) }
        assertEquals(HttpStatusCode.OK, response.status)
        val runtime = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val measurements = runtime.getValue("measurements").jsonObject
        val owner = runtime.getValue("owner_readings").jsonArray.single {
            "runtime_file_io_pending" in it.jsonObject.getValue("measurements").jsonObject
        }.jsonObject
        val owned = owner.getValue("measurements").jsonObject
        val ids = listOf(
            "accepted", "completed", "failed", "failed_writes", "dropped", "pending", "running", "scheduled",
            "queued", "peak_pending", "queue_wait_ms", "run_ms", "peak_queue_wait_ms", "peak_run_ms",
        ).map { "runtime_file_io_$it" }.toSet()
        assertEquals(ids, owned.keys)
        assertEquals("ok", JsonScalars.str(owner, "status"))
        owned.forEach { (id, value) ->
            assertEquals(value, measurements.getValue(id), id)
            assertTrue(value.jsonPrimitive.content.toDouble().let { it.isFinite() && it >= 0 }, id)
        }
        val pending = owned.getValue("runtime_file_io_pending").jsonPrimitive.content.toDouble()
        val running = owned.getValue("runtime_file_io_running").jsonPrimitive.content.toDouble()
        val scheduled = owned.getValue("runtime_file_io_scheduled").jsonPrimitive.content.toDouble()
        assertEquals(
            (pending - running - scheduled).coerceAtLeast(0.0),
            owned.getValue("runtime_file_io_queued").jsonPrimitive.content.toDouble(),
        )
        val schemaResponse = client.get("/api/perf/schema") { bearerAuth(bearer) }
        assertEquals(HttpStatusCode.OK, schemaResponse.status)
        val schema = Json.parseToJsonElement(schemaResponse.bodyAsText()).jsonObject.getValue("metrics").jsonObject
        ids.forEach { id ->
            val metric = schema.getValue(id).jsonObject
            assertEquals("gauge", JsonScalars.str(metric, "type"), id)
            assertEquals(if (id.endsWith("_ms")) "ms" else "count", JsonScalars.str(metric, "unit"), id)
            assertEquals(
                "last and monotonic time-weighted mean; never sum repeated cumulative samples",
                JsonScalars.str(metric, "aggregation"),
                id,
            )
        }
    }
}
