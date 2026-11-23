package splice.app.control.api.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import splice.app.control.ControlAuth
import splice.app.control.McpLaunchFolders
import splice.app.control.McpServerClasses
import splice.app.control.mount.McpMount
import splice.app.mcp.CodexRpcSync
import splice.app.mcp.CodexRpcSyncResult
import splice.app.mcp.McpFrontCheck
import splice.app.mcp.McpHarnessSyncSeams
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpSharing
import splice.control.mcp.McpHost
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpHarnessRoutesTest {
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, *>
    private lateinit var baseUrl: String
    private lateinit var mgmtKey: String
    private lateinit var homeDir: Path
    private lateinit var statePaths: StatePaths

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        homeDir = tmp.resolve("home")
        val stateDir = tmp.resolve("state")
        statePaths = StatePaths(baseOverride = stateDir, homeDir = homeDir)
        seedFixtures(homeDir)

        val keyStore = MgmtKey(statePaths)
        mgmtKey = keyStore.get()
        val accessKey = McpAccessKey(
            stateDir = stateDir,
            managementFile = statePaths.mgmtKeyFile,
            source = keyStore::get,
        )
        val guard = ControlAuth(keyStore, log = {}).guard()
        val sharing = McpSharing(true, emptySet(), "http://127.0.0.1:0/mcp/", accessKey, DirectoryProbe { false })
        val host = McpHost(sharing, { JsonObject(emptyMap()) }, log = {})
        val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") stateDir.toString() else null }
        val seams = McpHarnessSyncSeams(
            osName = "Linux",
            frontLive = McpFrontCheck { _, _ -> false },
            codexRpc = CodexRpcSync { _, _ -> CodexRpcSyncResult(ok = false, error = "rpc unavailable") },
            bearerOverride = { "SECRET-MCP-BEARER" },
        )
        val harnessRoutes = McpHarnessRoutes(statePaths, home = homeDir, env = env, seams = seams)
        val mount = McpMount(
            host,
            guard,
            McpLaunchFolders(null, McpServerClasses { sharing.plan(it) }, statePaths = statePaths),
            harnessesOverride = harnessRoutes,
        )
        server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing { mount.register(this) }
        }.start(wait = false)
        val boundPort = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$boundPort"
    }

    @BeforeEach
    fun resetFixtures() {
        Files.deleteIfExists(McpUserRegistry(statePaths).file)
        seedFixtures(homeDir)
    }

    @AfterAll
    fun tearDown() {
        server.stop(100, 500)
        client.close()
    }

    @Test
    fun `harness routes require management auth, validate inputs, and redact secrets`() = runBlocking {
        val unauth = client.get("$baseUrl/api/mcp/harnesses")
        assertEquals(HttpStatusCode.Unauthorized, unauth.status)

        val initial = client.get("$baseUrl/api/mcp/harnesses") {
            header("Authorization", "Bearer $mgmtKey")
        }
        assertEquals(HttpStatusCode.OK, initial.status)
        val initialText = initial.bodyAsText()
        assertNoSecrets(initialText)
        val initialJson = json.parseToJsonElement(initialText).jsonObject
        val availNames = initialJson.getValue("available").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue("codex-only" in availNames)
        assertTrue("ide-only" in availNames)

        val badImport = client.post("$baseUrl/api/mcp/harnesses/import") {
            header("Authorization", "Bearer $mgmtKey")
            contentType(ContentType.Application.Json)
            setBody("""{"sources":["bogus"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badImport.status)
    }

    @Test
    fun `harness routes import, sync to agy and codex, and fail closed on malformed registry`() = runBlocking {
        val imported = client.post("$baseUrl/api/mcp/harnesses/import") {
            header("Authorization", "Bearer $mgmtKey")
            contentType(ContentType.Application.Json)
            setBody("""{"servers":["codex-only"]}""")
        }
        assertEquals(HttpStatusCode.OK, imported.status)
        val importText = imported.bodyAsText()
        assertNoSecrets(importText)
        val importCount = json.parseToJsonElement(importText).jsonObject
            .getValue("imported").jsonPrimitive.content.toInt()
        assertEquals(1, importCount)

        val synced = client.post("$baseUrl/api/mcp/harnesses/sync") {
            header("Authorization", "Bearer $mgmtKey")
            contentType(ContentType.Application.Json)
            setBody("""{"harnesses":["agy","codex"]}""")
        }
        assertEquals(HttpStatusCode.OK, synced.status)
        val syncText = synced.bodyAsText()
        assertNoSecrets(syncText)
        val syncJson = json.parseToJsonElement(syncText).jsonObject
        assertEquals("true", syncJson.getValue("ok").jsonPrimitive.content)

        val agyConfig = Files.readString(homeDir.resolve(".gemini").resolve("config").resolve("mcp_config.json"))
        assertTrue("codex-only" in agyConfig)
        assertTrue("eli-brain" in agyConfig)

        val registryFile = McpUserRegistry(statePaths).file
        val savedRegistry = Files.readString(registryFile)
        try {
            Files.writeString(registryFile, "{broken")
            val failSync = client.post("$baseUrl/api/mcp/harnesses/sync") {
                header("Authorization", "Bearer $mgmtKey")
                contentType(ContentType.Application.Json)
                setBody("""{"harnesses":["agy"]}""")
            }
            assertEquals(HttpStatusCode.InternalServerError, failSync.status)
            assertEquals("{broken", Files.readString(registryFile))
        } finally {
            Files.writeString(registryFile, savedRegistry)
        }
    }

    private fun seedFixtures(home: Path) {
        Files.createDirectories(home.resolve(".gemini").resolve("config"))
        Files.createDirectories(home.resolve(".codex"))
        Files.writeString(
            home.resolve(".claude.json"),
            """{"mcpServers":{"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp?key=SECRET-URL",""" +
                """"headers":{"Authorization":"Bearer SECRET-BRAIN"}},""" +
                """"ast-grep":{"command":"uvx","args":["ast-grep-server"]}}}""",
        )
        Files.writeString(
            home.resolve(".gemini").resolve("config").resolve("mcp_config.json"),
            """{"mcpServers":{"ide-only":{"command":"node","args":["ide.js"]}}}""",
        )
        Files.writeString(
            home.resolve(".codex").resolve("config.toml"),
            """
            model = "o3"
            [mcp_servers.codex-only]
            url = "https://codex-only.example/mcp"
            """.trimIndent(),
        )
    }

    private fun assertNoSecrets(text: String) {
        assertFalse("SECRET-BRAIN" in text, text)
        assertFalse("SECRET-URL" in text, text)
        assertFalse("SECRET-MCP-BEARER" in text, text)
    }
}
