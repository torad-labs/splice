// NEW: a failed cancellation-state save cannot replace the original cancellation signal.
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.CodeModeCall
import splice.spi.CodeModeCell
import splice.spi.CodeModeResult
import splice.spi.CodeModeRuntime
import splice.spi.CodeModeStep
import java.nio.file.Files
import java.nio.file.Path

class CodexCodeModeCancellationPersistenceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `runtime startup cancellation survives failed lost-state save`() = runTest {
        val cancellation = CancellationException("original startup cancellation")
        val runtime = CancellingRuntime(tempDir.resolve("bridge.json"), cancellation, cancelAt = 0)
        val manager = bridge(runtime)
        var caught: CancellationException? = null
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        } catch (error: CancellationException) {
            caught = error
        }
        assertSame(cancellation, caught)
        assertTrue(runtime.startupCleaned)
        assertEquals(1, runtime.starts)
        assertEquals(0, runtime.cell.advances)
    }

    @Test
    fun `initial cell cancellation survives failed save and closes attached cell`() = runTest {
        val cancellation = CancellationException("original initial advance cancellation")
        val runtime = CancellingRuntime(tempDir.resolve("bridge.json"), cancellation, cancelAt = 1)
        val manager = bridge(runtime)
        var caught: CancellationException? = null
        try {
            manager.interceptor(turn(), disableParallel = false)
                .intercept(BASE_REQUEST, RecordingSink()) { outerOutcome() }
        } catch (error: CancellationException) {
            caught = error
        }
        assertSame(cancellation, caught)
        assertTrue(runtime.cell.closed)
        assertEquals(1, runtime.cell.advances)
    }

    @Test
    fun `resumed cell cancellation survives failed save and closes attached cell`() = runTest {
        val cancellation = CancellationException("original resumed advance cancellation")
        val runtime = CancellingRuntime(tempDir.resolve("bridge.json"), cancellation, cancelAt = 2)
        val manager = bridge(runtime)
        val sink = RecordingSink()
        manager.interceptor(turn(), disableParallel = false)
            .intercept(BASE_REQUEST, sink) { outerOutcome() }
        val id = sink.tools.single().id
        var caught: CancellationException? = null
        try {
            manager.interceptor(turn(id, "result"), disableParallel = false)
                .intercept(requestWithResult(id, "result"), RecordingSink()) { error("must not post") }
        } catch (error: CancellationException) {
            caught = error
        }
        assertSame(cancellation, caught)
        assertTrue(runtime.cell.closed)
        assertEquals(2, runtime.cell.advances)
        assertEquals(1, runtime.starts)
    }

    private class CancellingRuntime(
        private val state: Path,
        private val cancellation: CancellationException,
        private val cancelAt: Int,
    ) : CodeModeRuntime {
        var starts = 0
        var startupCleaned = false
        val cell = CancellingCell()

        override suspend fun start(source: String, tools: Set<String>): CodeModeCell {
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
            Files.delete(state)
            Files.createDirectory(state)
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
