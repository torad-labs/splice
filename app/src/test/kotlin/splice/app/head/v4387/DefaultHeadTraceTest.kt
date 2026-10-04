package splice.app.head.v4387

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.head.HeadTraceStores
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.perf.PerfSnapshot
import splice.core.terminal.TerminalOutput
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.EnvReader
import splice.head.trace.TraceCommand
import splice.head.trace.TraceHeadSource
import splice.head.trace.TraceHeads
import splice.head.wire.ClientInbound
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class DefaultHeadTraceTest {
    private fun clientBody(dir: Path): String? {
        val output = StringBuilder()
        val command = TraceCommand(
            TerminalOutput { output.appendLine(it) },
            TerminalOutput { error("unexpected trace failure: $it") },
            TraceHeadSource { TraceHeads.Configured("synthetic.toml", setOf("traced")) },
            { dir },
        )
        assertTrue(command.trace(listOf("traced", "--json"), EnvReader { null }))
        val record = Json.parseToJsonElement(output.toString()).jsonObject
        return (record["client"] as? JsonObject)?.get("body")?.jsonPrimitive?.content
    }

    private fun meta(): TurnMeta = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-traced--m",
        upstreamModel = "m",
        clientMaxTokens = 16,
        effort = "high",
        summary = "detailed",
        budgetTokens = null,
    )

    @Test
    fun `default head writes a private trace day and explicit false head constructs none`(@TempDir root: Path) {
        val topology = TopologyLoader.parse(
            """
            [providers.local]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:9/v1"
            auth = { kind = "api-key", env = "SYNTHETIC_KEY" }
            [[providers.local.models]]
            id = "m"
            context_window = 200000
            [heads.traced]
            provider = "local"
            port = 3102
            discovery_prefix = "claude-traced--"
            pinned_model = "m"
            [heads.untraced]
            provider = "local"
            port = 3103
            discovery_prefix = "claude-untraced--"
            pinned_model = "m"
            [heads.untraced.overrides]
            trace = "false"
            """.trimIndent(),
        )
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val config = ConfigService(paths, perHeadOverrides = topology.heads.mapValues { it.value.overrides })
        val factory = HeadTraceStores(paths)
        val traced = requireNotNull(factory.forHead("traced", config.getConfig("traced")))
        assertNull(factory.forHead("untraced", config.getConfig("untraced")))
        traced.begin(meta(), ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))
            .finish("ok", PerfSnapshot(emptyMap(), emptyMap()))
        assertTrue(AsyncFileIo.drain(), "the default-on trace append must finish")
        val files = Files.list(paths.traceDir).use { it.toList() }
        val written = files.filter { it.fileName.toString().startsWith("traced-") && it.toString().endsWith(".jsonl") }
        assertTrue(written.size == 1, "one day for the default-on head: $files")
        assertEquals("synthetic request", clientBody(paths.traceDir))
        assertTrue(Files.exists(written.single().resolveSibling("${written.single().fileName}.bodies")))
        assertTrue(files.none { it.fileName.toString().startsWith("untraced-") }, "opted-out head wrote: $files")
    }
}
