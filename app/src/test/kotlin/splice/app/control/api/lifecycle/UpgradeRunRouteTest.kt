// NEW: V4-220 item 4 — `splice upgrade` from the console, over POST /api/upgrade and GET
// /api/upgrade/run. The real control server under the bearer and the real UpgradeRuns over a temp share
// dir; the launcher is the one fake, recording what it was asked to start and standing in for the
// transient unit's shell by writing the files that shell writes (its pid, its output, its exit code).
package splice.app.control.api.lifecycle

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.control.ControlServer
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.lifecycle.upgrade.UpgradeRunLauncher
import splice.lifecycle.upgrade.UpgradeRuns
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val BUSY = "An upgrade is already running."
private const val BAD_VERSION = "The version must be a release number like v0.4.1."
private const val BAD_TYPES = "The version must be a release number like v0.4.1, and rollback true or false."

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpgradeRunRouteTest {

    private val url: String get() = "http://127.0.0.1:${control.listeningPort}"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val launched = CopyOnWriteArrayList<Pair<Path, List<String>>>()
    private val logged = CopyOnWriteArrayList<String>()
    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var tmp: Path
    private lateinit var share: Path

    @Volatile private var launchFailure: String? = null

    /** Records the start and writes the pid the run's shell would: this JVM's, so the run reads alive. */
    private val launcher = UpgradeRunLauncher { dir, args ->
        launched += dir to args
        val failure = launchFailure
        if (failure == null) Files.writeString(dir.resolve("pid"), ProcessHandle.current().pid().toString())
        failure
    }

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("upgrade-run")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { logged += it },
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @BeforeEach
    fun reset() {
        launched.clear()
        logged.clear()
        launchFailure = null
        share = Files.createTempDirectory(tmp, "share")
        control.ports.upgradeRuns = runs()
    }

    /** A daemon's view of the share dir; a second one is the daemon that came back on the new jar. */
    private fun runs(): UpgradeRuns =
        UpgradeRuns(EnvReader { if (it == "SPLICE_SHARE_DIR") share.toString() else null }, launcher)

    /** RED before V4-220 item 4, on the live train-19 daemon (2026-09-25): POST /api/upgrade answered 405
     *  and GET /api/upgrade/run 404; the console could not upgrade. */
    @Test
    fun `an upgrade starts out of process, never with --now, and the daemon that comes back reports it`() =
        runBlocking {
            val started = post("/api/upgrade", """{"to":"v9.9.9"}""")
            assertEquals(HttpStatusCode.Accepted, started.status, started.bodyAsText())
            val run = body(started)["run"]!!.jsonObject
            assertEquals(listOf("upgrade", "--to", "v9.9.9"), strings(run, "args"))
            assertEquals("running", run["state"]!!.jsonPrimitive.content)
            val (dir, args) = launched.single()
            assertEquals(listOf("upgrade", "--to", "v9.9.9"), args)
            assertEquals(run["id"]!!.jsonPrimitive.content, dir.fileName.toString())
            val line = "[control] upgrade: started ${dir.fileName} (upgrade --to v9.9.9)\n"
            assertEquals(listOf(line), logged.filter { it.startsWith("[control] upgrade") })

            assertEquals(HttpStatusCode.Conflict, post("/api/upgrade", "").status, "a second run started")
            assertEquals(BUSY, error(post("/api/upgrade", """{"rollback":true}""")))
            assertEquals(1, launched.size)

            Files.writeString(dir.resolve("output.log"), "\u001B[32m✓\u001B[0m staged     9.9.9\n")
            Files.writeString(dir.resolve("exit"), "0\n")
            control.ports.upgradeRuns = runs()
            val after = body(get("/api/upgrade/run"))["run"]!!.jsonObject
            assertEquals("succeeded", after["state"]!!.jsonPrimitive.content)
            assertEquals("0", after["exit_code"]!!.jsonPrimitive.content)
            assertEquals(listOf("✓ staged     9.9.9"), strings(after, "output"))
            assertTrue(launched.none { "--now" in it.second }, "the console asked for --now: $launched")
        }

    @Test
    fun `a rollback runs the rollback, and a failed run reports its exit`() = runBlocking {
        assertEquals(JsonNull, body(get("/api/upgrade/run"))["run"])
        val started = post("/api/upgrade", """{"rollback":true}""")
        assertEquals(HttpStatusCode.Accepted, started.status, started.bodyAsText())
        val (dir, args) = launched.single()
        assertEquals(listOf("upgrade", "--rollback"), args)
        Files.writeString(dir.resolve("exit"), "1\n")
        val failed = body(get("/api/upgrade/run"))["run"]!!.jsonObject
        assertEquals("failed", failed["state"]!!.jsonPrimitive.content)
        assertEquals("1", failed["exit_code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Accepted, post("/api/upgrade", "{}").status, "an ended run holds nothing")
    }

    @Test
    fun `refusals carry their own sentence and the status that fits, and launch nothing`() = runBlocking {
        val cases = mapOf(
            """{"to":"latest"}""" to (HttpStatusCode.BadRequest to BAD_VERSION),
            """{"to":"--now"}""" to (HttpStatusCode.BadRequest to BAD_VERSION),
            """{"to":"v01.2.3"}""" to (HttpStatusCode.BadRequest to BAD_VERSION),
            """{"to":"v1.2.3","rollback":true}""" to
                (HttpStatusCode.BadRequest to "A rollback returns to the previous release, so it takes no version."),
            """{"to":5}""" to (HttpStatusCode.BadRequest to BAD_TYPES),
            """{"rollback":"yes"}""" to (HttpStatusCode.BadRequest to BAD_TYPES),
            "not json" to (HttpStatusCode.BadRequest to BAD_TYPES),
        )
        for ((request, expected) in cases) {
            val response = post("/api/upgrade", request)
            assertEquals(expected.first, response.status, request)
            assertEquals(expected.second, error(response), request)
        }
        assertEquals(emptyList<Pair<Path, List<String>>>(), launched.toList())
    }

    @Test
    fun `a run the launcher cannot start answers 500 and leaves no run behind`() = runBlocking {
        launchFailure = "systemd-run exited 1"
        val refused = post("/api/upgrade", "{}")
        assertEquals(HttpStatusCode.InternalServerError, refused.status)
        assertEquals("The upgrade could not be started: systemd-run exited 1.", error(refused))
        assertEquals(JsonNull, body(get("/api/upgrade/run"))["run"])
    }

    @Test
    fun `the routes sit behind the management key and answer a named 503 unwired`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, withTimeout(TIMEOUT_MS) { client.post("$url/api/upgrade") }.status)
        assertEquals(HttpStatusCode.Unauthorized, withTimeout(TIMEOUT_MS) { client.get("$url/api/upgrade/run") }.status)
        control.ports.upgradeRuns = null
        val unwired = post("/api/upgrade", "{}")
        assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status)
        assertEquals("Upgrading from the console is not wired on this daemon.", error(unwired))
        assertEquals(HttpStatusCode.ServiceUnavailable, get("/api/upgrade/run").status)
        assertTrue(launched.isEmpty())
    }

    private fun strings(obj: JsonObject, field: String): List<String> =
        obj[field]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun body(response: HttpResponse): JsonObject =
        runBlocking { json.parseToJsonElement(response.bodyAsText()).jsonObject }

    private fun error(response: HttpResponse): String = body(response)["error"]!!.jsonPrimitive.content

    private suspend fun post(path: String, body: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        awaitPort()
        client.post("$url$path") {
            header("Authorization", "Bearer $key")
            setBody(body)
        }
    }

    private suspend fun get(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        awaitPort()
        client.get("$url$path") { header("Authorization", "Bearer $key") }
    }

    private suspend fun awaitPort() {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { ServerSocket(control.listeningPort).close() }.isFailure) return
            delay(POLL_MS)
        }
        error("the control server never bound :${control.listeningPort}")
    }
}
