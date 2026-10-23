package splice.provider.codex

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.provider.codex.stream.CodeModeSourceCommit
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import java.io.IOException

class CodeModeSourceCursorCertificationTest {
    @Test
    fun `available reads wait for terminal certification after item completion`() = runBlocking {
        val committed = mutableListOf<String>()
        val source = CodeModeSourceBuffer(CodeModeSourceCommit { committed += it })
        source.publish("synthetic prefix")
        val cursor = source.view()

        source.seal()

        assertEquals(null, cursor.readAvailable())
        assertTrue(committed.isEmpty())

        source.complete("synthetic prefix and certified suffix")

        assertEquals(
            CodeModeSourcePart.Complete("synthetic prefix and certified suffix"),
            cursor.readAvailable(),
        )
        assertEquals(listOf("synthetic prefix and certified suffix"), committed)
    }

    @Test
    fun `startup rejection disposes a concurrently returned cell`() {
        val source = CodeModeSourceBuffer()
        var closed = 0
        val cell = object : CodeModeCell {
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                error("rejected startup must not advance")
            override fun close() {
                closed++
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                source.whileStarting {
                    source.fail("synthetic rejected source")
                    cell
                }
            }
        }
        assertEquals(1, closed, "a returned cell must not be orphaned when source rejection wins")
        assertTrue(source.startupRejected)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `certified source imposes no deadline on a healthy pending startup`() = runTest {
        val source = CodeModeSourceBuffer()
        source.complete("synthetic certified source")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var closed = 0
        val cell = object : CodeModeCell {
            override suspend fun advance(results: List<CodeModeResult>): CodeModeStep =
                error("startup only")
            override fun close() {
                closed++
            }
        }
        val boot = async {
            source.whileStarting {
                entered.complete(Unit)
                release.await()
                cell
            }
        }
        entered.await()
        advanceTimeBy(901_000)
        assertFalse(boot.isCompleted, "healthy startup must not acquire a blanket execution timeout")
        release.complete(Unit)
        assertTrue(boot.await() === cell)
        assertEquals(0, closed)
        assertFalse(source.startupRejected)
        cell.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "prefix"])
    fun `more source bytes cannot reopen a sealed item`(prefix: String) {
        val source = CodeModeSourceBuffer()
        source.publish(prefix)
        source.seal()
        assertThrows(IllegalStateException::class.java) { source.publish(prefix + "extra") }
    }
}
