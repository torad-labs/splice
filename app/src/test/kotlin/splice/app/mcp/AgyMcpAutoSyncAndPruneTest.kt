package splice.app.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import splice.upstream.Ticker
import splice.upstream.Waiter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class AgyMcpAutoSyncAndPruneTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `agy writer tracks managed entries, prunes removed servers including the last one, and keeps backups`() {
        val state = tmp.resolve("state")
        val paths = StatePaths(baseOverride = state, homeDir = tmp)
        val writer = AgyMcpConfigWriter(paths, AgyMcpStaleEntries { _, _ -> false })
        val configFile = tmp.resolve(".gemini").resolve("config").resolve("mcp_config.json")
        Files.createDirectories(configFile.parent)
        Files.writeString(
            configFile,
            """{"otherKey":"keep","mcpServers":{"user-only":{"command":"node","args":["local.js"]}}}""",
        )

        val first = mapOf(
            "eli-brain" to httpEntry("https://mcp.torad.ai/mcp", "Authorization" to "Bearer TOKEN"),
            "ast-grep" to httpEntry("http://127.0.0.1:3096/mcp/ast-grep", "X-Splice-Head" to "agy"),
        )
        assertTrue(writer.sync(configFile, first))
        assertEquals(setOf("ast-grep", "eli-brain"), AgyMcpManagedState(paths).read())
        assertEquals("keep", readRoot(configFile).getValue("otherKey").jsonPrimitive.content)
        assertEquals(setOf("ast-grep", "eli-brain", "user-only"), readServers(configFile).keys)
        if ("posix" in configFile.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(configFile)))
        }

        assertTrue(writer.sync(configFile, first.filterKeys { it == "eli-brain" }))
        assertEquals(setOf("eli-brain", "user-only"), readServers(configFile).keys)
        assertEquals(setOf("eli-brain"), AgyMcpManagedState(paths).read())

        assertTrue(writer.sync(configFile, emptyMap()))
        assertEquals(setOf("user-only"), readServers(configFile).keys)
        assertTrue(AgyMcpManagedState(paths).read().isEmpty())
        assertFalse(writer.sync(configFile, emptyMap()), "subsequent empty sync is a no-op")

        val backups = state.resolve("mcp-sync-backups")
        val backupNames = Files.list(backups).use { s -> s.map { it.fileName.toString() }.toList() }
        assertTrue(backupNames.size >= 2, "backups=$backupNames")
        assertTrue(backupNames.all { it.startsWith("agy-mcp_config.json.bak-") })
    }

    @Test
    fun `agy writer removes pre-existing splice loopback entries even before managed state existed`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state-legacy"), homeDir = tmp)
        val writer = AgyMcpConfigWriter(paths, AgyMcpStaleEntries { _, _ -> false })
        val configFile = tmp.resolve("mcp_config.json")
        Files.writeString(
            configFile,
            """{"mcpServers":{""" +
                """"old-hosted":{"serverUrl":"http://127.0.0.1:3096/mcp/old-hosted",""" +
                """"headers":{"X-Splice-Head":"agy"}},""" +
                """"user-remote":{"serverUrl":"https://user.example/mcp"}}}""",
        )

        assertTrue(writer.sync(configFile, emptyMap()))
        assertEquals(setOf("user-remote"), readServers(configFile).keys)
    }

    @Test
    fun `dead entry pruning removes broken absolute scripts and unreachable temple but keeps valid servers`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state-dead"), homeDir = tmp)
        val configFile = seedMixedDeadAndValidConfig(tmp.resolve("mcp_config.json"))
        val logs = mutableListOf<String>()
        val writer = AgyMcpConfigWriter(paths, AgyMcpStaleEntries { _, _ -> false }, log = { logs += it })

        assertTrue(writer.sync(configFile, mapOf("eli-brain" to httpEntry("https://mcp.torad.ai/mcp"))))
        val servers = readServers(configFile)
        assertFalse("dead-script" in servers)
        assertFalse("dead-binary" in servers)
        assertFalse("temple" in servers)
        assertTrue("disabled-missing" in servers, "explicitly disabled user entry preserved")
        assertTrue("valid-script" in servers, "existing absolute script preserved")
        assertTrue("relative-script" in servers, "relative script arg preserved")
        assertTrue("inline-eval" in servers, "inline eval arg preserved")
        assertTrue("pkg-runner" in servers, "package runner preserved")
        assertTrue("user-loopback" in servers, "non-temple loopback server preserved")
        assertTrue("user-remote" in servers, "remote user server preserved")
        assertTrue("eli-brain" in servers, "synced server present")
        assertTrue(logs.any { "pruned stale entries" in it && "dead-script" in it && "temple" in it })
    }

    @Test
    fun `reachable temple loopback entry is preserved during sync`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state-temple"), homeDir = tmp)
        val reachableFile = tmp.resolve("reachable-temple.json")
        Files.writeString(reachableFile, """{"mcpServers":{"temple":{"serverUrl":"http://127.0.0.1:7740/mcp"}}}""")
        val writer = AgyMcpConfigWriter(paths, AgyMcpStaleEntries { _, port -> port == 7740 })

        assertFalse(writer.sync(reachableFile, emptyMap()))
        assertTrue("temple" in readServers(reachableFile))
    }

    @Test
    fun `exa query-string api key is promoted to x-api-key header while keeping other query params`() {
        val writer = AgyMcpConfigWriter(StatePaths(baseOverride = tmp.resolve("state-exa"), homeDir = tmp))
        val encoded = requireNotNull(
            writer.encodeForAgy(httpEntry("https://mcp.exa.ai/mcp?exaApiKey=test%2Bkey-123&tools=web_search_exa")),
        )
        assertEquals("https://mcp.exa.ai/mcp?tools=web_search_exa", encoded.getValue("serverUrl").jsonPrimitive.content)
        val encodedHeaders = encoded.getValue("headers").jsonObject
        assertEquals("test+key-123", encodedHeaders.getValue("x-api-key").jsonPrimitive.content)

        val bareExa = requireNotNull(writer.encodeForAgy(httpEntry("https://mcp.exa.ai/mcp?exaApiKey=only-key")))
        assertEquals("https://mcp.exa.ai/mcp", bareExa.getValue("serverUrl").jsonPrimitive.content)
        assertEquals("only-key", bareExa.getValue("headers").jsonObject.getValue("x-api-key").jsonPrimitive.content)

        val normalized = requireNotNull(
            McpEntryNormalizer().normalize(httpEntry("https://mcp.exa.ai/mcp?exaApiKey=import-key"), agyShape = false),
        )
        assertEquals("https://mcp.exa.ai/mcp", normalized.getValue("url").jsonPrimitive.content)
        assertEquals(
            "import-key",
            normalized.getValue("headers").jsonObject.getValue("x-api-key").jsonPrimitive.content,
        )
    }

    @Test
    fun `exa normalizer keeps explicit auth headers and leaves non-exa hosts untouched`() {
        val writer = AgyMcpConfigWriter(StatePaths(baseOverride = tmp.resolve("state-exa-2"), homeDir = tmp))
        val explicitAuth = requireNotNull(
            writer.encodeForAgy(
                httpEntry("https://mcp.exa.ai/mcp?exaApiKey=query-key", "Authorization" to "Bearer header-wins"),
            ),
        )
        assertEquals("https://mcp.exa.ai/mcp", explicitAuth.getValue("serverUrl").jsonPrimitive.content)
        val authHeaders = explicitAuth.getValue("headers").jsonObject
        assertEquals("Bearer header-wins", authHeaders.getValue("Authorization").jsonPrimitive.content)
        assertFalse("x-api-key" in authHeaders)

        val otherHost = requireNotNull(
            writer.encodeForAgy(httpEntry("https://other.example/mcp?exaApiKey=untouched")),
        )
        assertEquals(
            "https://other.example/mcp?exaApiKey=untouched",
            otherHost.getValue("serverUrl").jsonPrimitive.content,
        )
        assertFalse("headers" in otherHost)
    }

    @Test
    fun `auto-sync respects opt-in and never touches agy config when agy is not connected`() = runBlocking {
        val home = tmp.resolve("home-unconnected")
        val state = tmp.resolve("state-unconnected")
        Files.createDirectories(home)
        val claudeJson = home.resolve(".claude.json")
        Files.writeString(
            claudeJson,
            """{"mcpServers":{"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"}}}""",
        )
        val agyFile = home.resolve(".gemini").resolve("config").resolve("mcp_config.json")
        Files.createDirectories(agyFile.parent)
        val initialAgy = """{"mcpServers":{"mine":{"command":"node","args":["mine.js"]}}}"""
        Files.writeString(agyFile, initialAgy)

        val paths = StatePaths(baseOverride = state, homeDir = home)
        val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") state.toString() else null }
        val autoSync = McpHarnessAutoSync(home, paths, env, {}, testSeams(), waiter = Waiter {})

        assertFalse(autoSync.syncOnStartup())
        assertEquals(initialAgy, Files.readString(agyFile))

        Files.writeString(
            claudeJson,
            """{"mcpServers":{"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"},""" +
                """"second":{"type":"http","url":"https://second.example/mcp"}}}""",
        )
        Files.setLastModifiedTime(claudeJson, FileTime.from(Instant.now().plusSeconds(5)))
        assertFalse(autoSync.checkForChanges())
        assertEquals(initialAgy, Files.readString(agyFile))
        assertFalse(Files.exists(AgyMcpManagedState(paths).file))
    }

    @Test
    fun `auto-sync runs at startup, reacts to claude json edits, ignores noise, and fails safe`(): Unit =
        runBlocking {
            val fixture = seedConnectedFixture(tmp.resolve("home-connected"), tmp.resolve("state-connected"))
            val logs = mutableListOf<String>()
            val waits = mutableListOf<Long>()
            val ticks = mutableListOf<CompletableDeferred<Boolean>>()
            val autoSync = fixture.watcher(logs, waits, ticks, testSeams())
            val both = """"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"},""" +
                """"exa":{"type":"http","url":"https://mcp.exa.ai/mcp?exaApiKey=test-key"}"""
            val onlyBrain = """"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"}"""

            coroutineScope {
                autoSync.watch(this)
                while (ticks.isEmpty()) yield()

                val afterStart = readServers(fixture.agyFile)
                assertEquals(setOf("eli-brain", "exa", "user-only"), afterStart.keys)
                val exa = afterStart.getValue("exa").jsonObject
                assertEquals("https://mcp.exa.ai/mcp", exa.getValue("serverUrl").jsonPrimitive.content)
                assertEquals("test-key", exa.getValue("headers").jsonObject.getValue("x-api-key").jsonPrimitive.content)

                val mtimeBefore = Files.getLastModifiedTime(fixture.agyFile)
                fixture.writeClaude(2, both, 10)
                driveTick(ticks)
                assertEquals(1, waits.size)
                assertEquals(mtimeBefore, Files.getLastModifiedTime(fixture.agyFile))

                fixture.writeClaude(3, onlyBrain, 20)
                driveTick(ticks)
                assertEquals(setOf("eli-brain", "user-only"), readServers(fixture.agyFile).keys)

                Files.writeString(fixture.claudeJson, "{broken-half-written-json")
                Files.setLastModifiedTime(fixture.claudeJson, FileTime.from(Instant.now().plusSeconds(30)))
                driveTick(ticks)
                assertEquals(setOf("eli-brain", "user-only"), readServers(fixture.agyFile).keys)
                assertTrue(logs.any { "unreadable or malformed" in it })

                fixture.writeClaude(4, "", 40)
                driveTick(ticks)
                assertEquals(setOf("user-only"), readServers(fixture.agyFile).keys)
                assertTrue(readRoot(AgyMcpManagedState(fixture.paths).file).getValue("managed").jsonArray.isEmpty())

                ticks.removeFirst().complete(false)
            }
        }

    private fun seedMixedDeadAndValidConfig(configFile: Path): Path {
        val validScript = tmp.resolve("valid-server.mjs")
        Files.writeString(validScript, "console.log('ok')\n")
        val missingScript = tmp.resolve("missing").resolve("mcp_proxy_bundle.js")
        val missingBinary = tmp.resolve("missing").resolve("bin-server")
        Files.writeString(
            configFile,
            """{"mcpServers":{""" +
                """"dead-script":{"command":"node","args":["$missingScript"],"disabled":false},""" +
                """"dead-binary":{"command":"$missingBinary","disabled":false},""" +
                """"temple":{"serverUrl":"http://127.0.0.1:7740/mcp"},""" +
                """"disabled-missing":{"command":"node","args":["$missingScript"],"disabled":true},""" +
                """"valid-script":{"command":"node","args":["$validScript"],"disabled":false},""" +
                """"relative-script":{"command":"node","args":["ide.js"]},""" +
                """"inline-eval":{"command":"node","args":["-e","console.log(1)"]},""" +
                """"pkg-runner":{"command":"uvx","args":["custom-mcp-server"]},""" +
                """"user-loopback":{"serverUrl":"http://127.0.0.1:7740/mcp"},""" +
                """"user-remote":{"serverUrl":"https://custom.example/mcp"}}}""",
        )
        return configFile
    }

    private fun seedConnectedFixture(home: Path, state: Path): WatcherFixture {
        Files.createDirectories(home)
        val claudeJson = home.resolve(".claude.json")
        val agyFile = home.resolve(".gemini").resolve("config").resolve("mcp_config.json")
        Files.createDirectories(agyFile.parent)
        val missingScript = tmp.resolve("gone.mjs")
        Files.writeString(
            agyFile,
            """{"mcpServers":{"user-only":{"command":"node","args":["ok.js"]},""" +
                """"stale-one":{"command":"node","args":["$missingScript"]}}}""",
        )
        val paths = StatePaths(baseOverride = state, homeDir = home)
        McpUserRegistry(paths).rememberHarnesses(setOf(McpHarness.AGY))
        return WatcherFixture(home, state, paths, claudeJson, agyFile).also {
            it.writeClaude(
                1,
                """"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"},""" +
                    """"exa":{"type":"http","url":"https://mcp.exa.ai/mcp?exaApiKey=test-key"}""",
                0,
            )
        }
    }

    private suspend fun driveTick(ticks: MutableList<CompletableDeferred<Boolean>>) {
        ticks.removeFirst().complete(true)
        while (ticks.isEmpty()) yield()
    }

    private fun httpEntry(url: String, vararg headers: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "http")
        put("url", url)
        if (headers.isNotEmpty()) {
            putJsonObject("headers") { headers.forEach { (k, v) -> put(k, v) } }
        }
    }

    private fun readRoot(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    private fun readServers(file: Path): JsonObject = readRoot(file).getValue("mcpServers").jsonObject

    private fun testSeams(): McpHarnessSyncSeams = McpHarnessSyncSeams(
        osName = "Linux",
        frontLive = McpFrontCheck { _, _ -> false },
        codexRpc = CodexRpcSync { _, _ -> CodexRpcSyncResult(ok = false, error = "rpc unavailable") },
        bearerOverride = { "TEST-BEARER" },
        agyPortProbe = LoopbackPortProbe { _, _ -> false },
    )

    private data class WatcherFixture(
        val home: Path,
        val state: Path,
        val paths: StatePaths,
        val claudeJson: Path,
        val agyFile: Path,
    ) {
        fun writeClaude(numStartups: Int, serversBody: String, plusSeconds: Long) {
            Files.writeString(claudeJson, """{"numStartups":$numStartups,"mcpServers":{$serversBody}}""")
            if (plusSeconds > 0) {
                Files.setLastModifiedTime(claudeJson, FileTime.from(Instant.now().plusSeconds(plusSeconds)))
            }
        }

        fun watcher(
            logs: MutableList<String>,
            waits: MutableList<Long>,
            ticks: MutableList<CompletableDeferred<Boolean>>,
            seams: McpHarnessSyncSeams,
        ): McpHarnessAutoSync {
            val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") state.toString() else null }
            val ticker = Ticker {
                val next = CompletableDeferred<Boolean>()
                ticks += next
                next.await()
            }
            return McpHarnessAutoSync(
                home = home,
                paths = paths,
                env = env,
                log = { logs += it },
                seams = seams,
                ticker = ticker,
                waiter = Waiter { ms -> waits += ms },
            )
        }
    }
}
