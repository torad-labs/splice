// NEW: retention and rejected startup cannot keep error text outside the record's heap reservation.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

class CodeModeErrorBudgetTest(@param:TempDir private val dir: Path) {
    private val heap = HeapBudget(Long.MAX_VALUE, 512 * 1024)
    private val registry = CodexCodeModeRegistry(
        CodeModeBridgeConfig(
            runtimes = { error("synthetic error accounting must not start a worker") },
            state = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json")),
        ),
        Json { encodeDefaults = true },
        5.minutes,
        heap = heap,
    )
    private val record = CodeModeRecords.of("synthetic-error", 1)
    private val cell = ErrorBudgetCell()

    @Test
    fun `parking charges its retained error and closes the cell`() {
        try {
            parkedAdmission()
            val before = charge()
            registry.retainedCells.park(record, "synthetic retention ".repeat(128))
            val growth = CodeModeWeight.STORED.text(checkNotNull(record.error)) - CodeModeWeight.STORED.text("")
            assertEquals(before + growth, charge(), "the newly retained string must spend its own bytes")
            assertEquals(CodeModePhase.LOST, record.phase)
            assertTrue(cell.closed)
        } finally {
            registry.timed.finish {}
        }
    }

    @Test
    fun `parking at capacity closes the cell without retaining an uncharged error`() {
        try {
            parkedAdmission()
            val before = charge()
            checkNotNull(heap.reserve(heap.available.value)).use {
                registry.retainedCells.park(record, "synthetic retention ".repeat(128))
                assertEquals("", record.error, "the precharged empty error is the only retained fallback")
                assertEquals(before, charge())
                assertEquals(CodeModePhase.LOST, record.phase)
                assertTrue(cell.closed)
            }
        } finally {
            registry.timed.finish {}
        }
    }

    @Test
    fun `a rejected startup keeps its error charged when its save completes`() {
        try {
            assertTrue(registry.add(record))
            registry.startup.stop()
            val before = charge()
            assertFalse(registry.attach(record, cell))
            val growth = CodeModeWeight.STORED.text(checkNotNull(record.error)) - CodeModeWeight.STORED.text("")
            assertTrue(charge() >= before + growth)
            assertEquals(CodeModePhase.LOST, record.phase)
            assertTrue(cell.closed)
        } finally {
            registry.timed.finish {}
        }
    }

    @Test
    fun `a rejected startup at capacity cannot install error text before its save refuses`() {
        try {
            assertTrue(registry.add(record))
            registry.startup.stop()
            val before = charge()
            checkNotNull(heap.reserve(heap.available.value)).use {
                val failure = assertThrows<Exception> { registry.attach(record, cell) }
                assertTrue(failure is HeapCapacityException || failure.cause is HeapCapacityException)
                assertEquals("", record.error, "a refused snapshot must not leave an uncharged live string")
                assertEquals(before, charge())
                assertEquals(CodeModePhase.LOST, record.phase)
                assertTrue(cell.closed)
            }
        } finally {
            registry.timed.finish {}
        }
    }

    private fun parkedAdmission() {
        assertTrue(registry.add(record))
        assertTrue(registry.attach(record, cell))
        registry.retainedCells.release(record)
    }

    private fun charge(): Long = checkNotNull(record.heapLease).bytes
}

private class ErrorBudgetCell : CodeModeCell {
    var closed = false
        private set

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
        error("synthetic error accounting must not execute source")

    override fun close() {
        closed = true
    }
}
