package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.codemode.host.HostLaunch
import splice.codemode.host.PoolLimits
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeWorkerLostException

class CodeModeCapacityTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    @Timeout(60)
    fun `a parked script never queues a new script behind a legacy worker hint`(capacity: Int) = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = capacity),
            launch = HostLaunch(classpath = testClasspath),
        ).use { runtime ->
            val held = List(capacity) { runtime.start("return await tools.call('Read', {});", setOf("Read")) }
            try {
                held.forEach { assertTrue(it.advance() is CodeModeStep.Calls) }
                val cell = withTimeout(5_000) { runtime.start("return 'immediate';", emptySet()) }
                assertEquals("immediate", (cell.advance() as CodeModeStep.Completed).output)
                held.forEach {
                    val completed = it.advance(listOf(CodeModeResult("1", "held"))) as CodeModeStep.Completed
                    assertEquals("held", completed.output)
                }
            } finally {
                held.forEach { it.close() }
            }
        }
    }

    @Test
    @Timeout(60)
    fun `cancelling an active script leaves a parked sibling resumable`() = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1),
            launch = HostLaunch(classpath = testClasspath),
        ).use { runtime ->
            val held = runtime.start("return await tools.call('Read', {});", setOf("Read"))
            val running = runtime.start("await tools.call('Read', {}); while (true) {}", setOf("Read"))
            assertTrue(held.advance() is CodeModeStep.Calls)
            assertTrue(running.advance() is CodeModeStep.Calls)
            supervisorScope {
                val cancelled = async { running.advance(listOf(CodeModeResult("1", ""))) }
                kotlinx.coroutines.yield()
                cancelled.cancelAndJoin()
                val next = withTimeout(5_000) { runtime.start("return 'next';", emptySet()) }
                assertEquals("next", (next.advance() as CodeModeStep.Completed).output)
                val completed = held.advance(listOf(CodeModeResult("1", "alive"))) as CodeModeStep.Completed
                assertEquals("alive", completed.output)
            }
        }
    }

    @Test
    @Timeout(60)
    fun `closing the runtime during an admitted start is worker loss not caller cancellation`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val spawn = WorkerSpawn {
            entered.complete(Unit)
            java.util.concurrent.CountDownLatch(1).await()
            error("the cancelled spawn cannot finish")
        }
        JvmCodeModeRuntime(launch = HostLaunch(classpath = testClasspath, spawn = spawn)).use { runtime ->
            supervisorScope {
                val starting = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.start("return 'never';", emptySet())
                }
                entered.await()
                runtime.close()
                val failure = assertThrows(CodeModeStartException::class.java) { runBlocking { starting.await() } }
                assertTrue(failure.cause is CodeModeWorkerLostException)
            }
        }
    }

    @Test
    @Timeout(60)
    fun `closing the runtime closes parked cells and refuses new starts`() = runBlocking<Unit> {
        val runtime = JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1),
            launch = HostLaunch(classpath = testClasspath),
        )
        val held = runtime.start("await tools.call('Read', {});", setOf("Read"))
        assertTrue(held.advance() is CodeModeStep.Calls)
        runtime.close()
        assertThrows(CodeModeWorkerLostException::class.java) { runBlocking { held.advance() } }
        val failure = assertThrows(CodeModeStartException::class.java) {
            runBlocking { runtime.start("return 'never';", emptySet()) }
        }
        assertTrue(failure.cause is IllegalStateException)
    }
}
