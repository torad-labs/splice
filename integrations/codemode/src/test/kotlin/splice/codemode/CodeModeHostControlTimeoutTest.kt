// NEW: unanswered host control requests retain their charges only until a drained host is replaced.
package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.host.CodeModeHostDrains
import splice.codemode.host.CodeModePoolAdmission
import splice.codemode.host.CodeModePoolHost
import splice.core.util.LogSink
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeStartException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

@Timeout(30)
class CodeModeHostControlTimeoutTest {
    @Test
    fun `an unanswered engine close has a bounded retirement and replaces the drained host`() = runBlocking {
        val processes = ConcurrentLinkedQueue<SilentHostCloseProcess>()
        JvmCodeModeRuntime(
            maxWorkers = 1,
            workerStartTimeoutMs = 300,
            spawn = WorkerSpawn { SilentHostCloseProcess().also(processes::add) },
        ).use { runtime ->
            val cell = runtime.startSession("first", "return 'fixture';", emptySet())
            assertEquals("fixture", (cell.advance() as CodeModeStep.Completed).output)
            withTimeout(5_000) { while (runtime.liveCells() != 0) yield() }
            runtime.closeSession("first")
            withTimeout(5_000) { while (runtime.liveEngines() != 0) yield() }
            withTimeout(5_000) { while (processes.first().isAlive) yield() }
            assertFalse(processes.first().isAlive)
            val next = runtime.startSession("next", "return 'fixture';", emptySet())
            assertEquals("fixture", (next.advance() as CodeModeStep.Completed).output)
            assertEquals(2, processes.size)
        }
    }

    @Test
    fun `a start deadline quarantines an unanswered open before its retry can reuse the host`() = runBlocking {
        val processes = ConcurrentLinkedQueue<SilentHostCloseProcess>()
        val messages = ConcurrentLinkedQueue<String>()
        JvmCodeModeRuntime(
            maxWorkers = 1,
            workerStartTimeoutMs = 300,
            spawn = WorkerSpawn {
                SilentHostCloseProcess().also { process ->
                    process.holdEngineOpen = processes.isEmpty()
                    processes.add(process)
                }
            },
        ).also { it.observeHostLifecycle(LogSink(messages::add)) }.use { runtime ->
            assertThrows(CodeModeStartException::class.java) {
                runBlocking { runtime.startSession("hung", "return 'never';", emptySet()) }
            }
            withTimeout(5_000) { while (messages.none { it.contains("marked for replacement") }) yield() }
            withTimeout(5_000) { while (processes.first().isAlive) yield() }
            val retry = runtime.startSession("hung", "return 'fixture';", emptySet())
            assertEquals("fixture", (retry.advance() as CodeModeStep.Completed).output)
            assertEquals(
                2,
                processes.size,
                "The retry must boot a replacement instead of rejoining the unanswered open",
            )
        }
    }

    @Test
    fun `a stale control failure cannot quarantine a cleared or replacement generation`() = runBlocking<Unit> {
        SharedWorkerChannel(SilentHostCloseProcess(), this).use { old ->
            SharedWorkerChannel(SilentHostCloseProcess(), this).use { replacement ->
                old.awaitReady()
                replacement.awaitReady()
                old.close()
                val host = CodeModePoolHost()
                val drains = CodeModeHostDrains(
                    this,
                    ReentrantLock(),
                    CodeModePoolAdmission(1, DEFAULT_HEAP_MB, DEFAULT_POOL_MEMORY_MB),
                    LogSink {},
                )
                drains.failed(host, old)
                assertFalse(host.draining, "A cleared generation has nothing left to quarantine")
                host.boot = CompletableDeferred(replacement)
                drains.failed(host, old)
                assertFalse(host.draining, "A late old failure must not quarantine a healthy replacement")
                assertFalse(replacement.isClosed)
            }
        }
    }

    @Test
    fun `a dying control generation cannot quarantine its already cleared replacement slot`() = runBlocking {
        val processes = ConcurrentLinkedQueue<SilentHostCloseProcess>()
        JvmCodeModeRuntime(
            maxWorkers = 1,
            workerStartTimeoutMs = 300,
            spawn = WorkerSpawn { SilentHostCloseProcess().also(processes::add) },
        ).use { runtime ->
            val first = runtime.startSession("first", "return 'fixture';", emptySet())
            assertTrue(first.advance() is CodeModeStep.Completed)
            withTimeout(5_000) { while (runtime.liveCells() != 0) yield() }
            processes.first().exitOnEngineClose = true
            runtime.closeSession("first")
            withTimeout(5_000) { while (processes.first().isAlive) yield() }
            val next = withTimeout(5_000) { runtime.startSession("new", "return 'fixture';", emptySet()) }
            assertTrue(next.advance() is CodeModeStep.Completed)
            assertEquals(2, processes.size)
        }
    }

    @Test
    fun `a control fault quarantines admission but preserves a parked sibling until it drains`() = runBlocking {
        val processes = ConcurrentLinkedQueue<SilentHostCloseProcess>()
        val messages = ConcurrentLinkedQueue<String>()
        JvmCodeModeRuntime(
            maxWorkers = 1,
            workerStartTimeoutMs = 300,
            spawn = WorkerSpawn { SilentHostCloseProcess().also(processes::add) },
        ).also { it.observeHostLifecycle(LogSink(messages::add)) }.use { runtime ->
            val done = runtime.startSession("retiring", "return 'fixture';", emptySet())
            assertTrue(done.advance() is CodeModeStep.Completed)
            val held = runtime.startSession("parked", "return await tools.Read({});", setOf("Read"))
            assertTrue(held.advance() is CodeModeStep.Calls)
            withTimeout(5_000) { while (runtime.liveCells() != 1) yield() }
            runtime.closeSession("retiring")
            withTimeout(5_000) { while (messages.none { it.contains("marked for replacement") }) yield() }
            assertTrue(processes.first().isAlive, "A parked sibling prevents draining the host")
            assertEquals(2, runtime.liveEngines(), "The unconfirmed close retains its engine charge")
            val error = assertThrows(CodeModeCapacityException::class.java) {
                runBlocking {
                    withTimeout(5_000) { runtime.startSession("new", "return 'never';", emptySet()) }
                }
            }
            assertTrue(error.message.orEmpty().contains("quirks.code_mode_workers"))
            assertTrue(error.message.orEmpty().contains("quirks.code_mode_memory_mb"))
            val completed = held.advance(listOf(CodeModeResult("1", "alive"))) as CodeModeStep.Completed
            assertEquals("fixture", completed.output)
            withTimeout(5_000) { while (processes.first().isAlive) yield() }
            val next = runtime.startSession("new", "return 'fixture';", emptySet())
            assertTrue(next.advance() is CodeModeStep.Completed)
            assertEquals(2, processes.size)
        }
    }
}
