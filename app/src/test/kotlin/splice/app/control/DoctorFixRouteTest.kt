// NEW: V4-220 item 4 — POST /api/doctor/fix/{id}, the console's Fix button for the rows the daemon
// can fix itself. Driven end to end: the real control server under the bearer, the real doctor and
// the real `splice install --all`, against a temp install tree (bin, share, config, state) and a
// control port nothing listens on, so no ambient daemon or operator file is read or written.
//
// The contract under test is the V4-220 rule: an answer the console cannot mistake for success. 200
// only when doctor RE-RUN after the fix finds no row still carrying its id; a refusal is 409 with its
// own sentence, and both carry the report of that run.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.RunningJar
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.diagnostics.doctor.DoctorCommand
import splice.diagnostics.doctor.DoctorFixes
import splice.diagnostics.doctor.LocalRuntimeTransport
import splice.upstream.transport.LocalHttp
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private const val TIMEOUT_MS = 60_000L
private const val FIX_PATH = "/api/doctor/fix/install_all"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorFixRouteTest {

    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val logged = CopyOnWriteArrayList<String>()
    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("doctor-fix")
        val paths = StatePaths(baseOverride = tmp.resolve("daemon-state"))
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

    @Test
    fun `install_all links what doctor found unlinked and answers the re-run with no row left`() = runBlocking<Unit> {
        val tree = tree("applied", "claude-fixme")
        control.ports.doctorFixes = fixes(tree)

        val response = post(FIX_PATH)
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        val shim = tree.resolve("share").resolve("splice-launch")
        for (command in listOf("claude-fixme", "splice")) {
            val link = tree.resolve("bin").resolve(command)
            assertTrue(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == shim, "$command → $shim: $body")
        }
        val answer = json.parseToJsonElement(body).jsonObject
        assertEquals("install_all", answer.getValue("fix").jsonPrimitive.content)
        assertEquals(emptyList<String>(), rowsCalling(answer), "the re-run report still asks for install_all")
        assertTrue(logged.any { it == "[control] doctor fix install_all: applied\n" }, "$logged")
    }

    @Test
    fun `a refused install is a 409 with the verb's own sentence, and the rows that asked stay`() = runBlocking<Unit> {
        val tree = tree("refused", "claude-fixme", "claude-foreign")
        Files.writeString(tree.resolve("bin").resolve("claude-foreign"), "#!/bin/sh\n# the operator's own\n")
        control.ports.doctorFixes = fixes(tree)

        val response = post(FIX_PATH)
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Conflict, response.status, body)
        val answer = json.parseToJsonElement(body).jsonObject
        val error = answer.getValue("error").jsonPrimitive.content
        assertTrue(error.endsWith("claude-foreign exists and is not a symlink"), error)
        // claude-fixme and `splice` itself: install refused before linking either.
        assertEquals(listOf("installation/wrapper", "installation/wrapper"), rowsCalling(answer), body)
        val foreign = Files.readString(tree.resolve("bin").resolve("claude-foreign"))
        assertEquals("#!/bin/sh\n# the operator's own\n", foreign, "a refused install replaces nothing")
    }

    @Test
    fun `an id no fix has is a 404 naming the fixes there are`() = runBlocking<Unit> {
        control.ports.doctorFixes = fixes(tree("unknown", "claude-fixme"))
        val response = post("/api/doctor/fix/path")
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content
        assertEquals("no doctor fix by that name; this daemon runs install_all", error)
    }

    @Test
    fun `an unwired port is a named 503, never a fix that did nothing`() = runBlocking<Unit> {
        control.ports.doctorFixes = null
        val response = post(FIX_PATH)
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("doctor fixes are not wired"))
    }

    /** The ids of the report's rows whose `fix_id` is install_all. */
    private fun rowsCalling(answer: JsonObject): List<String> =
        answer.getValue("report").jsonObject.getValue("checks").jsonArray.map { it.jsonObject }
            .filter { row -> row["fix_id"]?.jsonPrimitive?.takeIf { it.isString }?.content == "install_all" }
            .map { it.getValue("id").jsonPrimitive.content }

    /** A temp install: the shim in share/, an empty bin/, one api-key head per [commands] entry. */
    private fun tree(name: String, vararg commands: String): Path {
        val root = Files.createDirectories(tmp.resolve(name))
        Files.createDirectories(root.resolve("bin"))
        Files.createDirectories(root.resolve("state"))
        val share = Files.createDirectories(root.resolve("share"))
        Files.writeString(share.resolve("splice-launch"), "#!/usr/bin/env node\n").toFile().setExecutable(true)
        val config = Files.createDirectories(root.resolve("config").resolve("splice"))
        Files.writeString(config.resolve("splice.toml"), topology(commands.toList()))
        return root
    }

    private fun topology(commands: List<String>): String = buildString {
        append(
            """
            [providers.openrouter]
            dialect = "openai-chat"
            base_url = "https://openrouter.example/api/v1"
            auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }

            [[providers.openrouter.models]]
            id = "m"
            context_window = 200000
            """.trimIndent(),
        )
        commands.forEachIndexed { i, command ->
            append("\n\n[heads.h$i]\nprovider = \"openrouter\"\nport = ${4600 + i}\n")
            append("discovery_prefix = \"$command--\"\npinned_model = \"m\"\n\n")
            append("[heads.h$i.claude]\ncommand = \"$command\"\n")
        }
    }

    private fun fixes(root: Path): DoctorFixes {
        val env = mapOf(
            "XDG_CONFIG_HOME" to root.resolve("config").toString(),
            "SPLICE_BIN_DIR" to root.resolve("bin").toString(),
            "SPLICE_SHARE_DIR" to root.resolve("share").toString(),
            "CLAUDEX_STATE_DIR" to root.resolve("state").toString(),
            "PATH" to root.resolve("bin").toString(),
            "SPLICE_CONTROL_PORT" to TestPorts.reserve().toString(),
        )
        val doctor = DoctorCommand(
            output = TerminalOutput { },
            errors = TerminalOutput { },
            jar = RunningJar { null },
            local = LocalRuntimeTransport { _, _, _ -> LocalHttp { _, _, _ -> null } },
        )
        return DoctorFixes(doctor, EnvReader { env[it] })
    }

    private suspend fun post(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.post("http://127.0.0.1:${control.listeningPort}$path") { header("Authorization", "Bearer $key") }
    }
}
