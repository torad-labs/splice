package splice.app.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.mcp.McpStdioCommand
import splice.app.cli.mcp.McpStdioPipes
import splice.app.mcp.stdio.McpStdioResolution
import splice.app.mcp.stdio.McpStdioTargets
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class McpDesktopSyncTest {
    @TempDir
    lateinit var tmp: Path

    private val home: Path get() = tmp.resolve("home")
    private val state: Path get() = tmp.resolve("state")
    private val paths: StatePaths get() = StatePaths(baseOverride = state, homeDir = home)
    private val env = EnvReader { key ->
        when (key) {
            "SPLICE_STATE_DIR" -> state.toString()
            "SPLICE_CONFIG" -> tmp.resolve("splice.toml").toString()
            "REMOTE_TOKEN" -> "from-env"
            else -> null
        }
    }
    private val seams = McpHarnessSyncSeams(
        osName = "Linux",
        frontLive = McpFrontCheck { _, _ -> false },
        bearerOverride = { "TEST-MCP-BEARER" },
    )

    @Test
    fun `sync to claude gives every gateway Desktop profile Splice's servers, bridged by name only`() = runBlocking {
        seedClaudeJson()
        val own = Files.createDirectories(home.resolve(".config/Claude-3p"))
        val beside = Files.createDirectories(home.resolve(".config/claude-splice/Claude-3p"))

        val report = McpHarnessSync(home, paths, env, LogSink {}, seams).sync(setOf(McpHarness.CLAUDE))
        val claude = report.outcomes.single()
        assertTrue(claude.ok, claude.detail)
        assertTrue(claude.changed)
        assertTrue(own.toString() in claude.detail && beside.toString() in claude.detail, claude.detail)

        val bridge = state.resolve("desktop-mcp").toAbsolutePath().toString()
        listOf(own, beside).forEach { profile ->
            val text = Files.readString(profile.resolve("claude_desktop_config.json"))
            val servers = Json.parseToJsonElement(text).jsonObject.getValue("mcpServers").jsonObject
            assertEquals(bridge, command(servers, "remote"))
            assertEquals(bridge, command(servers, "hosted"))
            assertEquals("uvx", command(servers, "scoped"))
            assertFalse("TEST-MCP-BEARER" in text || "example.invalid" in text || "REMOTE_TOKEN" in text)
        }
        assertTrue(Files.isExecutable(state.resolve("desktop-mcp")))
        val again = McpHarnessSync(home, paths, env, LogSink {}, seams).sync(setOf(McpHarness.CLAUDE))
        assertFalse(again.outcomes.single().changed, "a second sync changes nothing")
    }

    @Test
    fun `sync to claude with no gateway profile makes none and says so`() = runBlocking {
        seedClaudeJson()
        val report = McpHarnessSync(home, paths, env, LogSink {}, seams).sync(setOf(McpHarness.CLAUDE))
        val claude = report.outcomes.single()
        assertTrue(claude.ok)
        assertFalse(claude.changed)
        assertTrue("no gateway profile found" in claude.detail, claude.detail)
        assertFalse(Files.exists(home.resolve(".config/Claude-3p")))
    }

    @Test
    fun `the bridge finds a hosted server at Splice's hub and a remote one with its own headers expanded`() {
        seedClaudeJson()
        val targets = McpStdioTargets(home, paths, env, LogSink {}, seams)

        val hosted = targets.resolve("hosted") as McpStdioResolution.Found
        assertEquals("/mcp/hosted", hosted.target.url.path)
        assertEquals("127.0.0.1", hosted.target.url.host)
        assertEquals("Bearer TEST-MCP-BEARER", hosted.target.headers["Authorization"])

        val remote = targets.resolve("remote") as McpStdioResolution.Found
        assertEquals("https://example.invalid/mcp", remote.target.url.toString())
        assertEquals("Bearer from-env", remote.target.headers["Authorization"])
        assertFalse(remote.target.legacySse)
        assertTrue((targets.resolve("legacy") as McpStdioResolution.Found).target.legacySse)

        val scoped = targets.resolve("scoped") as McpStdioResolution.Missing
        assertTrue("Claude Desktop starts it itself" in scoped.why, scoped.why)
        assertTrue(targets.resolve("nope") is McpStdioResolution.Missing)
    }

    @Test
    fun `splice mcp stdio refuses a server it can't bridge on stderr and keeps stdout clean`() = runBlocking {
        seedClaudeJson()
        val errLines = mutableListOf<String>()
        val stdout = ByteArrayOutputStream()
        val command = McpStdioCommand(
            err = TerminalOutput { errLines += it },
            env = env,
            homeOverride = home,
            seams = seams,
            pipes = McpStdioPipes(ByteArrayInputStream(ByteArray(0)), stdout),
        )
        assertEquals(2, command.run(emptyList()))
        assertEquals(1, command.run(listOf("nope")))
        assertTrue(errLines.any { "no MCP server named 'nope'" in it }, errLines.toString())
        assertEquals(1, command.run(listOf("scoped")))
        assertEquals(0, stdout.size(), "stdout carries JSON-RPC and nothing else")
    }

    private fun command(servers: JsonObject, name: String): String =
        servers.getValue(name).jsonObject.getValue("command").jsonPrimitive.content

    private fun seedClaudeJson() {
        val project = Files.createDirectories(tmp.resolve("project"))
        Files.createDirectories(home)
        Files.writeString(
            home.resolve(".claude.json"),
            """
            {
              "mcpServers": {
                "hosted": {"command": "uvx", "args": ["hosted-mcp"]},
                "scoped": {"command": "uvx", "args": ["scoped-mcp", "$project"]},
                "remote": {"type": "http", "url": "https://example.invalid/mcp",
                           "headers": {"Authorization": "Bearer ${'$'}{REMOTE_TOKEN}"}},
                "legacy": {"type": "sse", "url": "https://example.invalid/sse"}
              }
            }
            """.trimIndent(),
        )
    }
}
