package splice.app.provider

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.parse.AnthropicParse
import splice.core.turn.WatchdogBudget
import splice.topology.TopologyLoader
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class MuseToolSurfaceTest {
    @Test
    fun `Muse defers its large tool set by default and keeps the search server-side`(@TempDir root: Path) = runTest {
        val turn = turn(root, null)
        val tools = turn.requestBody.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(12, tools.count { it["defer_loading"]?.jsonPrimitive?.content == "true" })
        assertEquals(1, tools.count { it["type"]?.jsonPrimitive?.content == "tool_search" })
        assertEquals("function", tools.first()["type"]?.jsonPrimitive?.content)
        assertNull(tools.last()["execution"], "Meta owns hosted search, not splice's client controller")
        assertNull(turn.toolSearch)
        assertEquals(1, turn.meta.toolsEager)
        assertEquals(12, turn.meta.toolsDeferred)
    }

    @Test
    fun `an explicit disabled surface leaves every Muse tool eager`(@TempDir root: Path) = runTest {
        val turn = turn(root, "enabled = false")
        val tools = turn.requestBody.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(13, tools.size)
        assertTrue(tools.none { "defer_loading" in it || it["type"]?.jsonPrimitive?.content == "tool_search" })
        assertNull(turn.meta.toolsDeferred)
    }

    @Test
    fun `an explicit surface table replaces Muse prefix defaults`(@TempDir root: Path) = runTest {
        val turn = turn(root, "defer_prefixes = [\"not-a-tool\"]")
        val tools = turn.requestBody.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(13, tools.size)
        assertTrue(tools.none { "defer_loading" in it || it["type"]?.jsonPrimitive?.content == "tool_search" })
        assertEquals(0, turn.meta.toolsDeferred)
    }

    private suspend fun turn(root: Path, surface: String?): splice.upstream.BuiltTurn {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val assembly = ProviderAssembly(
            paths,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
        )
        val extra = surface?.let { "[providers.muse.quirks.tool_surface]\n$it" }.orEmpty()
        val topology = TopologyLoader.parse(
            """
            [providers.muse]
            dialect = "openai-responses"
            base_url = "https://api.meta.ai/v1"
            auth = { kind = "muse-oauth", file = "${root.resolve("auth.json")}" }
            [[providers.muse.models]]
            id = "muse-spark-1.3"
            context_window = 1000000
            [heads.claude-muse]
            provider = "muse"
            port = 31393
            discovery_prefix = "claude-muse--"
            pinned_model = "muse-spark-1.3"
            $extra
            """.trimIndent(),
        )
        val head = topology.heads.getValue("claude-muse")
        val provider = topology.providers.getValue("muse")
        val ctx = ProviderBuild(
            key = "claude-muse",
            head = head,
            providerCfg = provider,
            catalog = provider.catalogFor(head),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = ConfigService(paths).getConfig("claude-muse"),
            loginCommand = "claude-muse login",
        )
        val tools = (0 until 12).joinToString(",") { index ->
            """{"name":"mcp__synthetic_$index","description":"Synthetic","input_schema":{"type":"object"}}"""
        }
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"muse-spark-1.3","messages":[{"role":"user","content":"synthetic"}],
            "tools":[{"name":"splice_exec","input_schema":{"type":"object"}},$tools]}""",
        )
        return assembly.buildProvider(ctx).provider.buildTurn(body, compact = false, sessionId = "synthetic-session")
    }
}
