package splice.app.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import splice.upstream.Waiter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class AgyMcpRegressionTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `combined POSIX shell inline and stdin flags are preserved while missing direct scripts are pruned`() {
        val probe = AgyMcpStaleEntries { _, _ -> false }
        val existing = tmp.resolve("start.sh")
        Files.writeString(existing, "#!/bin/sh\n")
        val missing = tmp.resolve("missing.sh")

        assertFalse(probe.isDead("lc", stdioEntry("bash", "-lc", "$existing --port 3000")))
        assertFalse(probe.isDead("ec", stdioEntry("sh", "-ec", "$existing serve")))
        assertFalse(probe.isDead("xc", stdioEntry("zsh", "-xc", "exec $missing")))
        assertFalse(probe.isDead("cmd", stdioEntry("bash", "--command", "$missing --flag")))
        assertFalse(probe.isDead("opt-c", stdioEntry("bash", "-o", "errexit", "-c", "$missing")))
        assertFalse(probe.isDead("stdin", stdioEntry("sh", "-s")))
        assertFalse(probe.isDead("stdin-login", stdioEntry("bash", "-ls", "$missing")))

        assertTrue(probe.isDead("direct-missing", stdioEntry("bash", "$missing")))
        assertTrue(probe.isDead("after-dashdash", stdioEntry("bash", "--", "$missing")))
        assertFalse(probe.isDead("direct-existing", stdioEntry("bash", "$existing")))
    }

    @Test
    fun `unreadable parent directory with EACCES is never pruned by stale detector`() {
        val probe = AgyMcpStaleEntries { _, _ -> false }
        if ("posix" !in tmp.fileSystem.supportedFileAttributeViews()) return
        val lockedDir = Files.createDirectories(tmp.resolve("locked-parent"))
        val hiddenScript = Files.writeString(lockedDir.resolve("server.mjs"), "console.log('ok')\n")
        val originalPerms = Files.getPosixFilePermissions(lockedDir)
        Files.setPosixFilePermissions(lockedDir, PosixFilePermissions.fromString("---------"))
        try {
            // Skip assertion only if process runs as root where chmod 000 still permits traversal.
            if (Files.notExists(hiddenScript)) return
            assertFalse(probe.isDead("inaccessible-script", stdioEntry("node", hiddenScript.toString())))
            assertFalse(probe.isDead("inaccessible-bin", stdioEntry(hiddenScript.toString())))
        } finally {
            Files.setPosixFilePermissions(lockedDir, originalPerms)
        }
    }

    @Test
    fun `single-read snapshot avoids TOCTOU second read of claude json during sync`() = runBlocking {
        val home = Files.createDirectories(tmp.resolve("home-toctou"))
        val state = tmp.resolve("state-toctou")
        val paths = StatePaths(baseOverride = state, homeDir = home)
        val claudeJson = home.resolve(".claude.json")
        Files.writeString(
            claudeJson,
            """{"mcpServers":{"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"}}}""",
        )
        McpUserRegistry(paths).rememberHarnesses(setOf(McpHarness.AGY))
        val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") state.toString() else null }

        val snapshot = McpStrictSyncSnapshot(home, paths, env).read()
        // Corrupt ~/.claude.json AFTER the strict snapshot was read: syncInputs must NOT re-read it.
        Files.writeString(claudeJson, "{torn-mid-write")

        val report = McpHarnessSync(home, paths, env, {}, testSeams()).syncInputs(snapshot.harnesses, snapshot.inputs)
        assertTrue(report.allOk)
        val agyFile = home.resolve(".gemini").resolve("config").resolve("mcp_config.json")
        val servers = Json.parseToJsonElement(Files.readString(agyFile)).jsonObject.getValue("mcpServers").jsonObject
        assertEquals(setOf("eli-brain"), servers.keys)
    }

    @Test
    fun `failed auto-sync does not settle state, backs off without busy-looping, and recovers when repaired`() =
        runBlocking {
            val home = Files.createDirectories(tmp.resolve("home-retry"))
            val state = tmp.resolve("state-retry")
            val paths = StatePaths(baseOverride = state, homeDir = home)
            Files.writeString(
                home.resolve(".claude.json"),
                """{"mcpServers":{"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"}}}""",
            )
            val agyFile = home.resolve(".gemini").resolve("config").resolve("mcp_config.json")
            Files.createDirectories(agyFile.parent)
            Files.writeString(agyFile, "{corrupt-agy")
            McpUserRegistry(paths).rememberHarnesses(setOf(McpHarness.AGY))
            val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") state.toString() else null }
            val logs = mutableListOf<String>()
            val autoSync = McpHarnessAutoSync(
                home,
                paths,
                env,
                { logs += it },
                testSeams(),
                timing = McpAutoSyncTiming(waiter = Waiter {}),
            )

            // Startup fails because mcp_config.json is malformed -> 1st failure schedules retry after 1 tick.
            assertFalse(autoSync.syncOnStartup())
            val failCountAfterStart = logs.count { "failed for agy" in it }
            assertEquals(1, failCountAfterStart)

            // Tick 1 retries and fails again -> 2nd failure backs off to 2 ticks.
            assertFalse(autoSync.checkForChanges())
            assertEquals(2, logs.count { "failed for agy" in it })

            // Tick 2 is inside backoff cooldown -> skips sync without busy-looping.
            assertFalse(autoSync.checkForChanges())
            assertEquals(2, logs.count { "failed for agy" in it })

            // Repair mcp_config.json WITHOUT touching ~/.claude.json; tick 3 retries and succeeds!
            Files.writeString(agyFile, """{"mcpServers":{}}""")
            assertTrue(autoSync.checkForChanges())
            val servers = Json.parseToJsonElement(Files.readString(agyFile)).jsonObject
                .getValue("mcpServers").jsonObject
            assertEquals(setOf("eli-brain"), servers.keys)

            // Subsequent tick is a silent no-op once settled.
            assertFalse(autoSync.checkForChanges())
        }

    @Test
    fun `exa normalizer uses RFC 3986 percent-decoding and preserves literal plus signs`() {
        val normalizer = McpUrlAuthNormalizer()
        val withPlus = normalizer.normalize(
            "https://mcp.exa.ai/mcp?exaApiKey=abc+def%2Bghi%20j&tools=web_search_exa",
            emptyMap(),
        )
        assertEquals("https://mcp.exa.ai/mcp?tools=web_search_exa", withPlus.url)
        assertEquals("abc+def+ghi j", withPlus.headers["x-api-key"])

        val invalidPct = normalizer.normalize("https://mcp.exa.ai/mcp?exaApiKey=bad%ZZ", emptyMap())
        assertEquals("https://mcp.exa.ai/mcp?exaApiKey=bad%ZZ", invalidPct.url)
        assertTrue(invalidPct.headers.isEmpty())
    }

    @Test
    fun `shared sync mutex serializes auto-sync against concurrent in-daemon manual sync`() = runBlocking {
        val home = Files.createDirectories(tmp.resolve("home-lock"))
        val state = tmp.resolve("state-lock")
        val paths = StatePaths(baseOverride = state, homeDir = home)
        Files.writeString(
            home.resolve(".claude.json"),
            """{"mcpServers":{"eli-brain":{"type":"http","url":"https://mcp.torad.ai/mcp"}}}""",
        )
        McpUserRegistry(paths).rememberHarnesses(setOf(McpHarness.AGY))
        val env = EnvReader { key -> if (key == "SPLICE_STATE_DIR") state.toString() else null }
        val sharedLock = Mutex()
        val autoSync = McpHarnessAutoSync(home, paths, env, {}, testSeams(), syncLock = sharedLock)

        sharedLock.lock()
        val started = CompletableDeferred<Unit>()
        val job = async {
            started.complete(Unit)
            autoSync.syncOnStartup()
        }
        started.await()
        repeat(2) { yield() }
        assertFalse(job.isCompleted, "auto-sync must wait while shared sync mutex is held")

        sharedLock.unlock()
        assertTrue(job.await())
    }

    private fun stdioEntry(command: String, vararg args: String): JsonObject = buildJsonObject {
        put("command", command)
        if (args.isNotEmpty()) {
            put("args", JsonArray(args.map(::JsonPrimitive)))
        }
    }

    private fun testSeams(): McpHarnessSyncSeams = McpHarnessSyncSeams(
        osName = "Linux",
        frontLive = McpFrontCheck { _, _ -> false },
        codexRpc = CodexRpcSync { _, _ -> CodexRpcSyncResult(ok = false, error = "rpc unavailable") },
        bearerOverride = { "TEST-BEARER" },
        agyPortProbe = LoopbackPortProbe { _, _ -> false },
    )
}
