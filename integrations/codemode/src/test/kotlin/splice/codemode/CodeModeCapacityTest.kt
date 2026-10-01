package splice.codemode

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.upstream.codemode.CodeModeStep

class CodeModeCapacityTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    @Timeout(60)
    fun `one more cell than the pool capacity completes without rejection`(capacity: Int) = runBlocking {
        JvmCodeModeRuntime(maxWorkers = capacity, workerClasspath = testClasspath).use { runtime ->
            val held = List(capacity) {
                runtime.start("await tools.call(\"Read\", {});", setOf("Read"))
            }
            supervisorScope {
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.start("return \"queued\";", emptySet())
                }
                try {
                    assertFalse(queued.isCompleted, "the extra cell waits instead of failing")
                    held.first().close()
                    val cell = withTimeout(30_000) { queued.await() }
                    assertEquals("queued", (cell.advance() as CodeModeStep.Completed).output)
                } finally {
                    held.forEach { it.close() }
                    queued.cancelAndJoin()
                }
            }
        }
    }

    @Test
    @Timeout(60)
    fun `cancelling a queued start does not take the next available permit`() = runBlocking {
        JvmCodeModeRuntime(maxWorkers = 1, workerClasspath = testClasspath).use { runtime ->
            val held = runtime.start("await tools.call(\"Read\", {});", setOf("Read"))
            supervisorScope {
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.start("return \"cancelled\";", emptySet())
                }
                assertFalse(cancelled.isCompleted)
                cancelled.cancelAndJoin()
                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.start("return \"next\";", emptySet())
                }
                held.close()
                val cell = withTimeout(30_000) { next.await() }
                assertEquals("next", (cell.advance() as CodeModeStep.Completed).output)
            }
        }
    }

    @Test
    @Timeout(60)
    fun `closing the runtime wakes a queued start without spawning another worker`() = runBlocking {
        val runtime = JvmCodeModeRuntime(maxWorkers = 1, workerClasspath = testClasspath)
        try {
            runtime.start("await tools.call(\"Read\", {});", setOf("Read"))
            supervisorScope {
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.start("return \"must not run\";", emptySet())
                }
                assertFalse(queued.isCompleted)
                runtime.close()
                val failure = assertThrows(IllegalStateException::class.java) {
                    runBlocking { withTimeout(5_000) { queued.await() } }
                }
                assertEquals("Code-mode runtime is closed", failure.message)
            }
        } finally {
            runtime.close()
        }
    }
}
