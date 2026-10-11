// NEW: a failed cancellation-state save cannot replace the original cancellation signal.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep

class CodexCodeModeCancellationPersistenceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `runtime startup cancellation survives failed lost-state save`() = runTest {
        val cancellation = CancellationException("original startup cancellation")
        val runtime = CancellingRuntime(stateFiles, cancellation, cancelAt = 0)
        val manager = bridge(runtime)
        var caught: CancellationException? = null
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { RoundResult.Outcome(outerOutcome()) }
        } catch (error: CancellationException) {
            caught = error
        }
        assertTrue(sameFailure(cancellation, caught), "the original cancellation survives: $caught")
        assertTrue(runtime.startupCleaned)
        assertEquals(1, runtime.starts)
        assertEquals(0, runtime.cell.advances)
    }

    @Test
    fun `initial cell cancellation survives failed save and closes attached cell`() = runTest {
        val cancellation = CancellationException("original initial advance cancellation")
        val runtime = CancellingRuntime(stateFiles, cancellation, cancelAt = 1)
        val manager = bridge(runtime)
        var caught: CancellationException? = null
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { RoundResult.Outcome(outerOutcome()) }
        } catch (error: CancellationException) {
            caught = error
        }
        assertTrue(sameFailure(cancellation, caught), "the original cancellation survives: $caught")
        assertTrue(runtime.cell.closed)
        assertEquals(1, runtime.cell.advances)
    }

    @Test
    fun `resumed cell cancellation survives failed save and closes attached cell`() = runTest {
        val cancellation = CancellationException("original resumed advance cancellation")
        val runtime = CancellingRuntime(stateFiles, cancellation, cancelAt = 2)
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { RoundResult.Outcome(outerOutcome()) }
        val id = sink.tools.single().id
        var caught: CancellationException? = null
        try {
            manager.interceptor(turn(id, "result"), disableParallel = false)
                .intercept(requestWithResult(id, "result"), RecordingSink()) { error("must not post") }
        } catch (error: CancellationException) {
            caught = error
        }
        assertTrue(sameFailure(cancellation, caught), "the original cancellation survives: $caught")
        assertTrue(runtime.cell.closed)
        assertEquals(2, runtime.cell.advances)
        assertEquals(1, runtime.starts)
    }

    private class CancellingRuntime(
        private val state: CodeModeStateFiles,
        private val cancellation: CancellationException,
        private val cancelAt: Int,
    ) : CodeModeRuntime {
        var starts = 0
        var startupCleaned = false
        val cell = CancellingCell()

        override suspend fun start(
            source: String,
            tools: Set<String>,
            descriptions: Map<String, String>,
        ): CodeModeCell {
            starts++
            if (cancelAt == 0) {
                try {
                    failSaveAndCancel()
                } finally {
                    startupCleaned = true
                }
            }
            return cell
        }

        override fun close() = cell.close()

        private fun failSaveAndCancel(): Nothing {
            state.block()
            throw cancellation
        }

        inner class CancellingCell : CodeModeCell {
            var advances = 0
            var closed = false
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
                advances++
                if (advances == cancelAt) failSaveAndCancel()
                return CodeModeStep.Calls(listOf(CodeModeCall("read", "Read", JsonObject(emptyMap()))))
            }

            override fun close() {
                closed = true
            }
        }
    }
}
