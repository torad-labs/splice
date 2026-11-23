// GET and PATCH /api/desktop/policy on a loopback control port, over a fixture home whose Claude Desktop library holds
// splice's configuration, applied: what Settings reads (the five groups, each key's default, the value Desktop is given
// and the console's own, the keys splice holds itself), what a PATCH refuses whole, and what one keeps: the console's
// value owner-only in the state folder, in Desktop's configuration at once, and back to the default on null.
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
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopPolicyLayers
import splice.app.desktop.setup.DesktopPolicyOverrides
import splice.app.desktop.setup.DesktopPolicyTarget
import splice.client.desktop.DesktopAppConfig
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The daemon's Desktop part over one fixture library on [door]: each publication is that library's. */
private class LibraryTarget(private val library: DesktopAppLibrary, private val door: String) : DesktopPolicyTarget {
    override val policyLayers: DesktopPolicyLayers get() = library.layers

    override suspend fun publishPolicy(reset: Set<String>): Int = if (library.publish(door, reset = reset)) 1 else 0

    override fun restartPending(): Boolean? = null
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopPolicyRoutesTest {
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val door = "http://127.0.0.1:3096/door"
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, *>
    private lateinit var baseUrl: String
    private lateinit var mgmtKey: String
    private lateinit var library: DesktopAppLibrary
    private lateinit var config: DesktopAppConfig
    private lateinit var overrides: Path
    private lateinit var home: Path

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        home = Files.createDirectories(tmp.resolve("home"))
        val vars = mapOf("HOME" to home.toString(), "XDG_CONFIG_HOME" to tmp.resolve("config").toString())
        val env = EnvReader { vars[it] }
        val paths = StatePaths(baseOverride = tmp.resolve("state"), homeDir = home)
        overrides = paths.desktopPolicyOverridesFile
        config = DesktopAppConfig(env, osName = "Linux")
        library = DesktopAppLibrary(config, managedPaths = emptyList(), overrides = DesktopPolicyOverrides(overrides))
        val keys = MgmtKey(paths)
        mgmtKey = keys.get()
        val guard = ControlAuth(keys, log = {}).guard()
        val target = LibraryTarget(library, door)
        val link = ClaudeLink(DaemonWrap { null })
        val routes = DesktopRoutes(ConsolePorts(), guard, link, PolicyTargetSource { target })
        server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { routes.register(this) }
        }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    @BeforeEach
    fun freshDesktop() {
        Files.deleteIfExists(overrides)
        Files.deleteIfExists(home.resolve(".claude/settings.json"))
        Files.deleteIfExists(home.resolve(".claude/plugins/known_marketplaces.json"))
        library.putIn(SPLICE_ID, door, home.resolve("helper"), held = null)
    }

    @AfterAll
    fun tearDown() {
        server.stop(100, 500)
        client.close()
    }

    @Test
    fun `the policy routes open only to the management key, and no answer is cached`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, client.get("$baseUrl/api/desktop/policy").status)
        val bare = client.patch("$baseUrl/api/desktop/policy") { jsonBody("""{"desktopHome":"simple"}""") }
        assertEquals(HttpStatusCode.Unauthorized, bare.status)
        assertFalse(Files.exists(overrides), "nothing kept for a caller without the key")

        val read = get()
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals("no-store", read.headers["Cache-Control"])
        val refused = patch("""{"nope":true}""")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals("no-store", refused.headers["Cache-Control"])
    }

    @Test
    fun `Settings reads five groups of keys, each with its default, and the keys splice holds itself`() = runBlocking {
        Files.createDirectories(home.resolve(".claude/plugins"))
        Files.writeString(home.resolve(".claude/settings.json"), """{"effortLevel":"low"}""")
        val market = """{"m":{"source":{"source":"url","url":"https://someone:tok3n-secret@example.com/m.json"}}}"""
        Files.writeString(home.resolve(".claude/plugins/known_marketplaces.json"), market)
        val text = get().bodyAsText()
        assertFalse("tok3n-secret" in text, "a URL's token is never shown")
        val body = json.parseToJsonElement(text).jsonObject
        val groups = body.getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(listOf("surfaces", "code", "extensions", "browser", "experience"), groups.map { it.str("id") })
        val keys = groups.flatMap { g -> g.getValue("keys").jsonArray.map { it.jsonObject } }
            .associateBy { it.str("key") }
        assertEquals(28, keys.size)
        val locked = body.getValue("locked").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("inferenceProvider" in locked && "deploymentMode" in locked)
        assertTrue(locked.none { it in keys }, "no locked key is editable")

        val timeout = keys.getValue("mcpToolTimeoutSec")
        assertEquals("integer", timeout.str("type"))
        assertEquals("60", timeout.str("min"))
        assertEquals("3600", timeout.str("max"))
        assertEquals("1800", timeout.str("default"))
        assertEquals("1800", timeout.str("effective"))
        assertEquals(JsonNull, timeout["override"])
        assertEquals("false", timeout.str("overridden"))
        val homes = keys.getValue("desktopHome").getValue("options").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("standard", "simple", "no-vm", "off"), homes)
        val effort = keys.getValue("defaultModelEffort")
        assertEquals("low", effort.str("default"), "the person's Claude Code effort is the default")
        assertEquals("false", body.str("restart_required"))
        assertEquals(JsonNull, body["overrides_error"])
    }

    @Test
    fun `a PATCH with any key the console does not set, or any value not of its kind, keeps nothing`() = runBlocking {
        val refusedBodies = listOf(
            """{"inferenceProvider":"bedrock"}""" to "inferenceProvider",
            """{"projectsEnabled":false,"deploymentMode":"1p"}""" to "deploymentMode",
            """{"notAKey":true}""" to "notAKey",
            """{"projectsEnabled":"yes"}""" to "projectsEnabled",
            """{"mcpToolTimeoutSec":5}""" to "mcpToolTimeoutSec",
            """{"mcpToolTimeoutSec":90.5}""" to "mcpToolTimeoutSec",
            """{"mcpToolTimeoutSec":"900"}""" to "mcpToolTimeoutSec",
            """{"desktopHome":"weird"}""" to "desktopHome",
            """{"coworkEgressAllowedHosts":["has space.com"]}""" to "coworkEgressAllowedHosts",
            """{"allowedPluginMarketplaces":[{"source":"svn","url":"x"}]}""" to "allowedPluginMarketplaces",
            """{"allowedPluginMarketplaces":[{"source":"url","url":"https://u:p@h.example/m.json"}]}""" to
                "allowedPluginMarketplaces",
            """{"claudeAiImport":{"bannerMaxDismissals":99}}""" to "claudeAiImport",
        )
        refusedBodies.forEach { (body, key) ->
            val answer = patch(body)
            assertEquals(HttpStatusCode.BadRequest, answer.status, body)
            val keys = json.parseToJsonElement(answer.bodyAsText()).jsonObject.getValue("keys").jsonObject
            assertTrue(key in keys, "$body names $key: $keys")
        }
        assertEquals(HttpStatusCode.BadRequest, patch("not json").status)
        assertEquals(HttpStatusCode.BadRequest, patch("{}").status)
        assertFalse(Files.exists(overrides), "a refused body keeps nothing")
        assertEquals("true", desktop().str("projectsEnabled"), "and changes nothing in Desktop")
    }

    @Test
    fun `a PATCH keeps the values owner-only and gives them to Desktop at once`() = runBlocking {
        val answer = patch("""{"desktopHome":"simple","mcpToolTimeoutSec":900,"coworkEgressAllowedHosts":["*.x.io"]}""")
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        val body = json.parseToJsonElement(answer.bodyAsText()).jsonObject
        assertEquals("1", body.str("published"))
        assertEquals("true", body.str("restart_required"), "Desktop reads it at its next launch")
        val timeout = key(body, "mcpToolTimeoutSec")
        assertEquals("900", timeout.str("override"))
        assertEquals("900", timeout.str("effective"))
        assertEquals("1800", timeout.str("default"))
        assertEquals("true", timeout.str("overridden"))

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(overrides)))
        val kept = json.parseToJsonElement(Files.readString(overrides)).jsonObject
        assertEquals(setOf("desktopHome", "mcpToolTimeoutSec", "coworkEgressAllowedHosts"), kept.keys)
        val written = desktop()
        assertEquals("simple", written.str("desktopHome"))
        assertEquals("900", written.str("mcpToolTimeoutSec"))
        val hosts = written.getValue("coworkEgressAllowedHosts").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("*.x.io"), hosts)
        assertEquals("gateway", written.str("inferenceProvider"), "splice's own keys are untouched")
        assertEquals("3p", written.str("deploymentMode"))
        val again = json.parseToJsonElement(get().bodyAsText()).jsonObject
        assertEquals("900", key(again, "mcpToolTimeoutSec").str("override"), "a later read says the same")
    }

    @Test
    fun `null puts a key back to its default, in the console and in Desktop`() = runBlocking {
        val set = patch("""{"mcpToolTimeoutSec":900,"codeDefaultPermissionMode":"plan"}""")
        assertEquals(HttpStatusCode.OK, set.status)
        assertEquals("plan", desktop().str("codeDefaultPermissionMode"))

        val answer = patch("""{"mcpToolTimeoutSec":null,"codeDefaultPermissionMode":null}""")
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        val timeout = key(json.parseToJsonElement(answer.bodyAsText()).jsonObject, "mcpToolTimeoutSec")
        assertEquals("false", timeout.str("overridden"))
        assertEquals("1800", timeout.str("effective"))
        val written = desktop()
        assertEquals("1800", written.str("mcpToolTimeoutSec"))
        assertFalse("codeDefaultPermissionMode" in written, "a key with no default is gone again")
        assertEquals(JsonObject(emptyMap()), json.parseToJsonElement(Files.readString(overrides)).jsonObject)
    }

    @Test
    fun `an overrides file that is no JSON is said, gives Desktop nothing, and is not written over`() = runBlocking {
        Files.createDirectories(overrides.parent)
        Files.writeString(overrides, "{not json")
        val body = json.parseToJsonElement(get().bodyAsText()).jsonObject
        assertTrue(body.str("overrides_error").contains("desktop-policy-overrides.json"), body.toString())
        assertEquals("1800", key(body, "mcpToolTimeoutSec").str("effective"))

        val answer = patch("""{"desktopHome":"simple"}""")
        assertEquals(HttpStatusCode.Conflict, answer.status)
        assertEquals("{not json", Files.readString(overrides))
        assertEquals("standard", desktop().str("desktopHome"))
    }

    @Test
    fun `a key splice holds itself, written into the overrides file by hand, is ignored`() = runBlocking {
        Files.createDirectories(overrides.parent)
        Files.writeString(overrides, """{"deploymentMode":"1p","desktopHome":"off"}""")
        val body = json.parseToJsonElement(get().bodyAsText()).jsonObject
        assertEquals(listOf("deploymentMode"), body.getValue("ignored").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("off", key(body, "desktopHome").str("effective"))

        assertEquals(HttpStatusCode.OK, patch("""{"keepAwakeEnabled":false}""").status)
        val written = desktop()
        assertEquals("3p", written.str("deploymentMode"))
        assertEquals("false", written.str("keepAwakeEnabled"))
    }

    private suspend fun get(): HttpResponse =
        client.get("$baseUrl/api/desktop/policy") { header("Authorization", "Bearer $mgmtKey") }

    private suspend fun patch(body: String): HttpResponse = client.patch("$baseUrl/api/desktop/policy") {
        header("Authorization", "Bearer $mgmtKey")
        jsonBody(body)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /** splice's configuration in the fixture library, as Desktop reads it. */
    private fun desktop(): JsonObject = json.parseToJsonElement(Files.readString(config.file(SPLICE_ID))).jsonObject

    private fun key(body: JsonObject, name: String): JsonObject = body.getValue("groups").jsonArray
        .flatMap { g -> g.jsonObject.getValue("keys").jsonArray.map { it.jsonObject } }
        .single { it.str("key") == name }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}

private const val SPLICE_ID = "0c0c0c0c-1111-4c2d-9e8f-123456789abc"
