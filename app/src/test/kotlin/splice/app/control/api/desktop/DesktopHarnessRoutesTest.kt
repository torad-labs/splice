// GET and PATCH /api/desktop/harnesses on a loopback control port, over a fixture harness map with a ChatGPT provider,
// a Claude one and a Grok one: what Settings and Accounts read (each family's harness and who chose it, every prefix's
// switch), what a PATCH refuses whole and leaves unwritten, what one keeps (the console's choice, owner-only, in the
// one file a Desktop session launcher reads), a reset, a restart that only Antigravity's switch needs, and a file
// splice can't read, which is reported and never written over.
package splice.app.control.api.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ClaudeLink
import splice.app.control.ConsolePorts
import splice.app.control.ControlAuth
import splice.app.control.DaemonWrap
import splice.app.control.DesktopRoutes
import splice.app.desktop.harness.DesktopHarnessFile
import splice.app.desktop.harness.DesktopHarnesses
import splice.app.desktop.harness.HarnessTarget
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The daemon's harness map over a fixture file; [served] is whether Antigravity's models are served. */
private class FixtureTarget(override val harnesses: DesktopHarnesses, var served: Boolean? = null) : HarnessTarget {
    override fun antigravityServed(): Boolean? = served
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopHarnessRoutesTest {
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val providers = mapOf("codex" to "openai", "claude" to "anthropic", "grok" to "xai")
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, *>
    private lateinit var baseUrl: String
    private lateinit var mgmtKey: String
    private lateinit var file: Path
    private lateinit var target: FixtureTarget

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val paths = StatePaths(baseOverride = tmp.resolve("state"), homeDir = home)
        file = paths.desktopHarnessesFile
        val keys = MgmtKey(paths)
        mgmtKey = keys.get()
        val guard = ControlAuth(keys, log = {}).guard()
        val routes = DesktopRoutes(
            ConsolePorts(),
            guard,
            ClaudeLink(DaemonWrap { null }),
            harnessTargets = HarnessTargetSource { target },
        )
        server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { routes.register(this) }
        }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    @BeforeEach
    fun freshMap() {
        Files.deleteIfExists(file)
        target = FixtureTarget(harnesses(antigravity = null))
    }

    @AfterAll
    fun tearDown() {
        server.stop(100, 500)
        client.close()
    }

    @Test
    fun `the harness routes open only to the management key, and no answer is cached`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, client.get("$baseUrl/api/desktop/harnesses").status)
        val bare = client.patch("$baseUrl/api/desktop/harnesses") { jsonBody("""{"openai":{"harness":"codex"}}""") }
        assertEquals(HttpStatusCode.Unauthorized, bare.status)
        assertFalse(Files.exists(file), "nothing kept for a caller without the key")

        val read = get()
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals("no-store", read.headers["Cache-Control"])
        val refused = patch("""{"nope":null}""")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals("no-store", refused.headers["Cache-Control"])
    }

    @Test
    fun `GPT sessions start on Claude Code through Splice until the person opts into Codex`() = runBlocking {
        target.served = true
        val body = body(get())
        assertEquals("claude-code", body.str("default_harness"))
        val gpt = family(body, "openai")
        assertEquals("claude-code", gpt.str("harness"))
        assertEquals("default", gpt.str("set_by"))
        assertEquals(listOf("claude-code", "codex"), gpt.getValue("choices").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(gpt.getValue("toggleable").jsonPrimitive.boolean)
        assertTrue(gpt.getValue("configured").jsonPrimitive.boolean, "a ChatGPT provider is in splice.toml")
        assertEquals(listOf("codex"), gpt.getValue("providers").jsonArray.map { it.jsonPrimitive.content })

        val agy = family(body, "antigravity")
        assertTrue(agy.getValue("enabled").jsonPrimitive.boolean)
        assertEquals("antigravity", agy.str("harness"))
        assertTrue(agy.getValue("configured").jsonPrimitive.boolean)

        val switches = switches(body)
        assertEquals("claude-code", switches.getValue("claude-codex--").str("harness"))
        assertEquals("claude-code", switches.getValue("claude-oai--").str("harness"))
        assertEquals("openai", switches.getValue("claude-oai--").str("family"))
        assertEquals("claude-code", switches.getValue("claude-grok--").str("harness"))
        assertEquals(JsonNull, switches.getValue("claude-grok--").getValue("family"))
        assertEquals("antigravity", switches.getValue("claude-antigravity--").str("harness"))
        assertFalse(body.getValue("restart_required").jsonPrimitive.boolean)
        assertEquals(JsonNull, body.getValue("overrides_error"))
    }

    @Test
    fun `choosing Codex keeps it owner-only in the one file a launcher reads, and null puts it back`() = runBlocking {
        val chosen = patch("""{"openai":{"harness":"codex"}}""")
        assertEquals(HttpStatusCode.OK, chosen.status, chosen.bodyAsText())
        val answer = body(chosen)
        assertEquals("codex", family(answer, "openai").str("harness"))
        assertEquals("console", family(answer, "openai").str("set_by"))
        assertEquals("codex", switches(answer).getValue("claude-oai--").str("harness"))
        assertFalse(answer.getValue("restart_required").jsonPrimitive.boolean, "a launcher reads GPT's at each session")

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        val kept = json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(1, kept.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("codex", switches(kept).getValue("claude-codex--").str("harness"))
        assertEquals("claude-code", switches(kept).getValue("claude-grok--").str("harness"))
        val keptGpt = kept.getValue("families").jsonArray.map { it.jsonObject }.single { it.str("family") == "openai" }
        assertEquals("console", keptGpt.str("set_by"))
        assertEquals("codex", family(body(get()), "openai").str("harness"), "read back from the file")

        val reset = body(patch("""{"openai":null}"""))
        assertEquals("claude-code", family(reset, "openai").str("harness"))
        assertEquals("default", family(reset, "openai").str("set_by"))
        val rewritten = switches(json.parseToJsonElement(Files.readString(file)).jsonObject)
        assertEquals("claude-code", rewritten.getValue("claude-codex--").str("harness"))
    }

    @Test
    fun `a PATCH with anything wrong in it is refused whole, and nothing is written`() = runBlocking {
        val wrong = listOf(
            """{}""" to "name at least one",
            """[]""" to "expected a JSON object",
            """{"gemini":{"harness":"antigravity"}}""" to "gemini: not a Desktop harness family",
            """{"openai":{"harness":"antigravity"}}""" to "openai: `antigravity` is not a harness openai runs on",
            """{"openai":{"harness":"vim"}}""" to "openai: `vim` is not a harness",
            """{"openai":{"harness":7}}""" to "openai: harness must be a harness id",
            """{"openai":{"enabled":false}}""" to "openai: openai sessions always run",
            """{"antigravity":{"enabled":"no"}}""" to "antigravity: enabled must be true or false",
            """{"antigravity":{"on":false}}""" to "antigravity: `on` is not a harness setting",
            """{"antigravity":{}}""" to "antigravity: name a harness or a switch",
            """{"antigravity":true}""" to "antigravity: expected",
            """{"openai":{"harness":"codex"},"nope":null}""" to "nope: not a Desktop harness family",
        )
        for ((sent, why) in wrong) {
            val answer = patch(sent)
            assertEquals(HttpStatusCode.BadRequest, answer.status, sent)
            val error = body(answer).str("error")
            assertTrue(error.startsWith(why), "$sent: $error")
        }
        assertFalse(Files.exists(file), "no refused PATCH writes the file")
    }

    @Test
    fun `Antigravity turned off waits for the daemon to start again, and splice-toml decides until it is set`() =
        runBlocking {
            target = FixtureTarget(harnesses(antigravity = false))
            val off = family(body(get()), "antigravity")
            assertFalse(off.getValue("enabled").jsonPrimitive.boolean)
            assertEquals("splice.toml", off.str("set_by"))

            val booted = harnesses(antigravity = null)
            assertTrue(booted.boot(), "absent reads as on")
            target = FixtureTarget(booted)
            val answer = body(patch("""{"antigravity":{"enabled":false}}"""))
            assertFalse(family(answer, "antigravity").getValue("enabled").jsonPrimitive.boolean)
            assertFalse(switches(answer).getValue("claude-antigravity--").getValue("enabled").jsonPrimitive.boolean)
            assertTrue(answer.getValue("restart_required").jsonPrimitive.boolean)
            assertFalse(harnesses(antigravity = null).boot(), "the next start reads the console's switch")
            val back = body(patch("""{"antigravity":{"enabled":true}}"""))
            assertFalse(back.getValue("restart_required").jsonPrimitive.boolean, "back to what the daemon runs")
        }

    @Test
    fun `a file splice can't read is reported, refused with 409, and never written over`() = runBlocking {
        Files.createDirectories(file.parent)
        val theirs = """{"version": 9, "families": "mine"}"""
        Files.writeString(file, theirs)
        val read = body(get())
        assertTrue(read.str("overrides_error").contains("version 9"), read.toString())
        assertEquals("claude-code", family(read, "openai").str("harness"), "splice's defaults meanwhile")

        val refused = patch("""{"openai":{"harness":"codex"}}""")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue(body(refused).str("error").contains("fix it or move it aside"))
        target.harnesses.publish()
        assertEquals(theirs, Files.readString(file), "neither a PATCH nor a start writes over it")
    }

    @Test
    fun `the daemon's start writes the whole map, and a console choice survives it`() = runBlocking {
        target.harnesses.publish()
        val first = json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("claude-code", switches(first).getValue("claude-codex--").str("harness"))
        patch("""{"openai":{"harness":"codex"}}""")
        harnesses(antigravity = null).publish()
        val again = json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("codex", switches(again).getValue("claude-codex--").str("harness"))
    }

    private fun harnesses(antigravity: Boolean?): DesktopHarnesses =
        DesktopHarnesses(DesktopHarnessFile(file), providers, antigravity) {}

    private suspend fun get(): HttpResponse =
        client.get("$baseUrl/api/desktop/harnesses") { header("Authorization", "Bearer $mgmtKey") }

    private suspend fun patch(body: String): HttpResponse = client.patch("$baseUrl/api/desktop/harnesses") {
        header("Authorization", "Bearer $mgmtKey")
        jsonBody(body)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun body(response: HttpResponse): JsonObject =
        json.parseToJsonElement(response.bodyAsText()).jsonObject

    private fun family(body: JsonObject, name: String): JsonObject =
        body.getValue("families").jsonArray.map { it.jsonObject }.single { it.str("family") == name }

    private fun switches(body: JsonObject): Map<String, JsonObject> =
        body.getValue("switches").jsonArray.map { it.jsonObject }.associateBy { it.str("prefix") }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
