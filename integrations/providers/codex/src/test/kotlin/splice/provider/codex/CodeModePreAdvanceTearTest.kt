// NEW: an ACTIVE owner can lose its source before resume or cell lookup without becoming invalid input.
// Oct 23, 2026: the two tears of a source still streaming went with streamed execution; a transport failure before a
// script is admitted stays an internal failure.
package splice.provider.codex

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.failure.PersistedCause
import splice.core.turn.TurnOutcome
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink
import java.io.IOException

class CodeModePreAdvanceTearTest : CodeModeStatementStreamSupport() {
    @Test
    @Timeout(20)
    fun `an IOException before source admission remains an internal failure`() = runBlocking {
        val runtime = IncrementalRuntime()
        val manager = bridge(runtime)
        val initial = StepSink()
        var posts = 0
        val post = object : RedirectableRoundPost {
            override suspend fun invoke(bodyJson: String): RoundResult = into(bodyJson, initial)

            override suspend fun into(bodyJson: String, sink: WireSink): RoundResult {
                posts++
                throw IOException("synthetic transport failure before script admission")
            }
        }
        try {
            val outcome = manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, initial, post).turn() as TurnOutcome.Failure
            assertEquals(PersistedCause.INTERNAL, outcome.cause.persisted)
            assertFalse(outcome.cause.deterministic)
            assertNull(outcome.partial)
            assertFalse(initial.callback.isCompleted)
            assertTrue(stateFiles.records().isEmpty(), "there was no script to admit or lose")
            assertEquals(0, runtime.starts)
            assertEquals(1, posts)
        } finally {
            manager.onHeadStop()
        }
    }
}
