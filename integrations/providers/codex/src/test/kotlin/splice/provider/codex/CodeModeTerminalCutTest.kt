// NEW: a client step that fails after the upstream delivered a round's terminal cuts nothing; one that fails mid-stream cuts.
package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.TurnOutcome
import splice.provider.codex.stream.CodeModeStreams

internal class CodeModeTerminalCutTest : CodeModeStatementStreamSupport() {
    private suspend fun cutsAfterDisposal(finished: Boolean): Long {
        val manager = bridge(IncrementalRuntime())
        val sink = StepSink()
        val source = GatedPost(sink)
        val streams: CodeModeStreams = manager.driver.streams
        try {
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, source).turn()
                as TurnOutcome.Success
            val key = stateFiles.records().single().getValue("key").jsonPrimitive.content
            val record = manager.registry.recordsFor(key).single()
            val round = checkNotNull(streams.find(record))
            val watched = streams.watchCuts(key)
            if (finished) {
                source.terminalGate = CompletableDeferred()
                source.gates[1].complete(Unit)
                source.gates[2].complete(Unit)
                source.complete.complete(Unit)
                withTimeout(30_000) { source.terminalSent.await() }
            }
            round.cancel()
            return streams.takeCuts(watched, emptyList()).also { source.terminalGate?.complete(Unit) }
        } finally {
            source.terminalGate?.complete(Unit)
            manager.onHeadStop()
        }
    }

    @Test
    @Timeout(60)
    fun `a step that fails between the terminal and the upstream-ended mark records no cut`() = runBlocking {
        assertEquals(0L, cutsAfterDisposal(finished = true))
    }

    @Test
    @Timeout(60)
    fun `a step that fails mid-stream still records its cut`() = runBlocking {
        assertEquals(1L, cutsAfterDisposal(finished = false))
    }
}
