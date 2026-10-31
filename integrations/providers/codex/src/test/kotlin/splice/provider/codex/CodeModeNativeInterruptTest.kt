package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import splice.provider.codex.stream.CodeModeHeldNativeCell
import splice.provider.codex.stream.CodeModeNativeCells
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import java.io.IOException

class CodeModeNativeInterruptTest {
    @Test
    fun `interrupt addresses only active cells of the exact owned conversation`() = runTest {
        val cells = CodeModeNativeCells()
        cells.services.trackSession("record-one", "one", "client")
        cells.services.trackSession("record-two", "two", "client")
        val active = Cell("active")
        val completed = Cell("completed")
        val sibling = Cell("sibling")
        cells.interrupts.started("one", CodeModeHeldNativeCell(active, "outer-active"))
        cells.retain("one", active, "outer-active")
        cells.interrupts.started("one", CodeModeHeldNativeCell(completed, "outer-completed"))
        cells.completed("one", completed.cellId, "outer-completed")
        cells.interrupts.started("two", CodeModeHeldNativeCell(sibling, "outer-sibling"))
        assertEquals(listOf(CodeModeInterruptedCell("active", true)), interrupted(cells, "one", "client"))
        assertEquals("client", cells.services.clientSession("one"))
        assertTrue(interrupted(cells, "one", "client").isEmpty())
        assertEquals(null, cells.takeRetained("one", "active"))
        assertEquals(listOf(CodeModeInterruptedCell("sibling", true)), interrupted(cells, "two", "client"))
    }

    @Test
    fun `missing wrong-client and evicted conversations cannot masquerade as owned idle state`() = runTest {
        val cells = CodeModeNativeCells()
        cells.services.trackSession("record", "owned", "client")
        unavailable(cells, "missing", "client")
        unavailable(cells, "owned", "another-client")
        assertTrue(interrupted(cells, "owned", "client").isEmpty())
        cells.closeSession("owned")
        unavailable(cells, "owned", "client")
    }

    @Test
    fun `new cells admitted after the explicit snapshot are not stopped by that earlier action`() = runTest {
        val cells = CodeModeNativeCells()
        cells.services.trackSession("record", "owned", "client")
        val first = Cell("before").also { it.release = CompletableDeferred() }
        cells.interrupts.started("owned", CodeModeHeldNativeCell(first, "first"))
        val interrupt = async { interrupted(cells, "owned", "client") }
        first.entered.await()
        val later = Cell("after")
        cells.interrupts.started("owned", CodeModeHeldNativeCell(later, "later"))
        first.release?.complete(Unit)
        assertEquals(listOf("before"), interrupt.await().map { it.cellId })
        assertEquals(listOf("after"), interrupted(cells, "owned", "client").map { it.cellId })
    }

    @Test
    fun `one native failure is visible while independent sibling terminations finish and remain retryable`() = runTest {
        val cells = CodeModeNativeCells()
        cells.services.trackSession("record", "owned", "client")
        val failed = Cell("failed").also { it.failure = IOException("transport closed") }
        val healthy = Cell("healthy")
        cells.interrupts.started("owned", CodeModeHeldNativeCell(failed, "first"))
        cells.interrupts.started("owned", CodeModeHeldNativeCell(healthy, "second"))
        val result = interrupted(cells, "owned", "client").associateBy { it.cellId }
        assertEquals("transport closed", result.getValue("failed").failure)
        assertTrue(result.getValue("healthy").terminated)
        failed.failure = null
        assertEquals(listOf(CodeModeInterruptedCell("failed", true)), interrupted(cells, "owned", "client"))
    }

    @Test
    fun `native cancellation propagates instead of turning into a successful interrupt report`() = runTest {
        val cells = CodeModeNativeCells()
        cells.services.trackSession("record", "owned", "client")
        val cancelled = Cell("cancelled").also { it.cancellation = CancellationException("actual cancellation") }
        cells.interrupts.started("owned", CodeModeHeldNativeCell(cancelled, "first"))
        try {
            interrupted(cells, "owned", "client")
            fail<Unit>("Native cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("actual cancellation", expected.message)
        }
        cancelled.cancellation = null
        assertTrue(interrupted(cells, "owned", "client").single().terminated)
    }

    private suspend fun unavailable(cells: CodeModeNativeCells, key: String, sessionId: String) {
        assertEquals(CodeModeInterruptOutcome.Unavailable, cells.interrupt(key, sessionId))
    }

    private suspend fun interrupted(
        cells: CodeModeNativeCells,
        key: String,
        sessionId: String,
    ): List<CodeModeInterruptedCell> =
        when (val result = cells.interrupt(key, sessionId)) {
            is CodeModeInterruptOutcome.Interrupted -> result.cells
            CodeModeInterruptOutcome.Unavailable -> fail("Expected an owned native conversation")
        }

    private class Cell(override val cellId: String) : NativeCodeModeCell {
        val entered = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null
        var failure: IOException? = null
        var cancellation: CancellationException? = null

        override suspend fun submitResults(results: List<CodeModeResult>) = Unit
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = CodeModeStep.Yielded(cellId, "")

        override suspend fun terminate(): CodeModeStep {
            entered.complete(Unit)
            release?.await()
            cancellation?.let { throw it }
            failure?.let { throw it }
            return CodeModeStep.Completed("", terminated = true)
        }

        override fun close() = Unit
    }
}
