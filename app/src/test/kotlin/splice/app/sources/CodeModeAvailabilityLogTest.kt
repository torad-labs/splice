package splice.app.sources

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.codex.CodeModeNativeRuntimes
import splice.codemode.DEFAULT_HEAP_MB
import splice.codemode.host.PoolLimits
import splice.core.index.WireBlockIndex
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.HeadScopedLogs
import splice.core.util.LogSink
import splice.diagnostics.logs.LogFileSource
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodeModeToolPolicy
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.catalog.CodeModePresentation
import splice.provider.codex.catalog.CodeModeTurnCatalog
import splice.upstream.RoundResult
import splice.upstream.codemode.availability.CodeModeToolMode
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path

class CodeModeAvailabilityLogTest {
    @Test
    fun `unavailable provider warning reaches the ordinary head log reader without model content`(
        @TempDir tmp: Path,
    ) = runTest {
        val lines = mutableListOf<String>()
        val manager = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                CodeModeNativeRuntimes(false, PoolLimits(), DEFAULT_HEAP_MB),
                CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                log = HeadScopedLogs.headScopedLog("availability", LogSink { lines += it }),
            ),
        )
        try {
            val turn = CodexCodeModeBridge.Turn(
                "actual-session",
                "actual-conversation",
                "preferred-model",
                emptySet(),
                catalog = CodeModeTurnCatalog(
                    policy = CodeModeToolPolicy(
                        presentation = CodeModePresentation(toolMode = CodeModeToolMode.CODE_MODE),
                    ),
                ),
            )
            val result = manager.interceptor(turn, disableParallel = false)
                .intercept("""{"input":[{"role":"developer","content":""}]}""", NoContent()) {
                    RoundResult.Outcome(TurnOutcome.Success(false, false, noRequestUsage))
                }
            assertTrue(result is RoundResult.Outcome && result.outcome is TurnOutcome.Success)
            val file = tmp.resolve("daemon.log")
            Files.writeString(file, lines.joinToString("\n", postfix = "\n") + "[other] unrelated notice\n")
            val tail = LogFileSource(file, "[availability]").tailLines(20)
            assertTrue(tail.all { it.startsWith("[availability]") }, tail.toString())
            assertEquals(
                listOf(
                    "[availability][code-mode] Code Mode is unavailable because code-mode host is disabled. " +
                        "Falling back to direct tools; enable `features.code_mode_host` " +
                        "and install `codex-code-mode-host`.",
                ),
                tail.filter { it.startsWith("[availability][code-mode] Code Mode is unavailable because ") },
            )
        } finally {
            manager.onProviderStop()
        }
    }

    private class NoContent : WireSink {
        override suspend fun openText(): WireBlockIndex = error("Availability is not model content")
        override suspend fun openThinking(): WireBlockIndex = error("Availability is not model content")
        override suspend fun openTool(id: String, name: String): WireBlockIndex = error("No client call was issued")
        override suspend fun textDelta(index: WireBlockIndex, text: String): Unit = error("No model block was opened")
        override suspend fun thinkingDelta(
            index: WireBlockIndex,
            thinking: String,
        ): Unit = error("No model block was opened")
        override suspend fun inputJsonDelta(
            index: WireBlockIndex,
            partialJson: String,
        ): Unit = error("No client call was issued")
        override suspend fun closeBlock(index: WireBlockIndex): Unit = error("No model block was opened")
        override suspend fun closeAll(): Unit = Unit
        override suspend fun addTextBlock(text: String): Unit = error("Availability is not model content")
        override suspend fun addRedactedThinking(data: String): Unit = error("Availability is not model content")
    }
}
