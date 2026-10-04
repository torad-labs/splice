// NEW: retained reader completion callbacks cannot throw through coroutine finalization.
package splice.provider.codex

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.upstream.LifecycleScope
import splice.upstream.RedirectableRoundPost
import splice.upstream.TurnEnd
import splice.upstream.sse.WireSink
import kotlin.time.Duration.Companion.hours

class CodeModeCompletionCallbackTest : CodeModeStatementStreamSupport() {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a throwing retained reader end callback is supervised and its message stays withheld`() = runTest {
        val lines = mutableListOf<String>()
        val config = CodeModeBridgeConfig({ error("runtime unused") }, stateLocation(), log = { lines += it })
        val registry = CodexCodeModeRegistry(config, Json, 1.hours)
        val round = CodeModeLiveRound(
            config,
            registry,
            CodexCodeModeWire(Json, {}),
            CodeModeStreamAdmission { error("no source to admit") },
            StepSink(),
        )
        val scope = LifecycleScope(StandardTestDispatcher(testScheduler))
        val terminal = TurnOutcome.Success(false, false, Usage())
        val post = object : RedirectableRoundPost {
            override suspend fun invoke(bodyJson: String): TurnOutcome = terminal
            override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome = terminal
        }
        var endings = 0
        try {
            assertDoesNotThrow {
                round.start(
                    scope,
                    post,
                    "{}",
                    TurnEnd {
                        endings++
                        error("synthetic private callback bytes")
                    },
                )
            }
            runCurrent()
            assertEquals(1, endings)
            assertTrue(lines.any { "source completion callback failed" in it }, lines.toString())
            assertFalse(lines.any { "synthetic private callback bytes" in it })
        } finally {
            scope.cancel()
        }
    }
}
