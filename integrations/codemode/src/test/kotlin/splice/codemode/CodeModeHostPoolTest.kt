// NEW: actual host death, guest execution and engine retirement stay isolated by session ownership.
package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.host.CodeModePoolAdmission
import splice.codemode.host.HostLaunch
import splice.codemode.host.PoolLimits
import splice.core.util.ElapsedClock
import splice.upstream.Ticker
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

@Timeout(90)
class CodeModeHostPoolTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `a running program does not block another session and its timeout leaves that cell alive`() = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1),
            launch = HostLaunch(classpath = testClasspath),
        ).use { runtime ->
            val first = runtime.startSession(
                "session-a",
                "await tools.Read({}); while (true) {}",
                setOf("Read"),
            )
            assertTrue(first.advance() is CodeModeStep.Calls)
            val runaway = async {
                try {
                    withTimeout(10_000) { first.advance(listOf(CodeModeResult("1", "go"))) }
                    error("The nonterminating program must time out")
                } catch (_: TimeoutCancellationException) {
                    // Cancellation must close only this context, not its session's host.
                }
            }
            withTimeout(5_000) { while (runtime.busyCells() != 1) yield() }
            assertFalse(runaway.isCompleted, "A is executing, not just parked")
            val sibling = withTimeout(5_000) {
                runtime.startSession("session-b", "return await tools.Read({value: 'independent'});", setOf("Read"))
            }
            assertTrue(sibling.advance() is CodeModeStep.Calls)
            assertEquals(2, runtime.liveEngines(), "concurrent sessions must own distinct native engines")
            withTimeout(15_000) { runaway.await() }
            val completed = result(sibling, "sibling survived")
            assertEquals("sibling survived", completed.output)
            assertEquals(null, completed.error)
        }
    }

    @Test
    fun `host death loses only pinned sessions and their next cells share a fresh replacement host`() = runBlocking {
        val spawned = ConcurrentLinkedQueue<Process>()
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 2),
            launch = HostLaunch(
                classpath = testClasspath,
                spawn = WorkerSpawn { it.start().also(spawned::add) },
            ),
        ).use { runtime ->
            val first = parked(runtime, "session-a")
            val sibling = parked(runtime, "session-b")
            val sameHost = parked(runtime, "session-c")
            assertEquals(2, spawned.size)
            // These are this test's synthetic child JVMs, never an operator process.
            spawned.first().destroyForcibly().waitFor()
            withTimeout(5_000) { while (runtime.liveEngines() != 1) yield() }
            assertThrows(IOException::class.java) { runBlocking { result(first, "lost") } }
            assertThrows(IOException::class.java) { runBlocking { result(sameHost, "lost") } }
            assertEquals("alive", result(sibling, "alive").output)
            val nextFirst = parked(runtime, "session-a")
            val nextThird = parked(runtime, "session-c")
            assertEquals(3, spawned.size, "both lost sessions must reuse the same freshly booted host")
            assertEquals("new-a", result(nextFirst, "new-a").output)
            assertEquals("new-c", result(nextThird, "new-c").output)
        }
    }

    @Test
    fun `host death interrupts a cell waiting for live source without waiting for upstream EOF`() = runBlocking {
        val spawned = ConcurrentLinkedQueue<Process>()
        val reading = CompletableDeferred<Unit>()
        val next = CompletableDeferred<CodeModeSourcePart>()
        var first = true
        val source = CodeModeSource {
            if (first) {
                first = false
                CodeModeSourcePart.Delta("const value = 1;\\n")
            } else {
                reading.complete(Unit)
                next.await()
            }
        }
        try {
            JvmCodeModeRuntime(
                limits = PoolLimits(maxWorkers = 1),
                launch = HostLaunch(
                    classpath = testClasspath,
                    spawn = WorkerSpawn { it.start().also(spawned::add) },
                ),
            ).use { runtime ->
                val cell = runtime.startStreamingSession("source-fixture", source, emptySet())
                val waiting = async {
                    try {
                        cell.advance()
                        error("A lost cell must not complete cleanly")
                    } catch (error: IOException) {
                        error
                    }
                }
                withTimeout(5_000) { reading.await() }
                spawned.single().destroyForcibly().waitFor()
                try {
                    assertTrue(withTimeout(5_000) { waiting.await() } is CodeModeWorkerLostException)
                } finally {
                    next.complete(CodeModeSourcePart.Complete(""))
                }
            }
        } finally {
            next.complete(CodeModeSourcePart.Complete(""))
        }
    }

    @Test
    fun `closing a session remains live when every bounded guest lane is executing`() = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1),
            launch = HostLaunch(classpath = testClasspath),
        ).use { runtime ->
            val cells = (0 until CodeModeHeap.maxExecutionsPerSession).map {
                runtime.startSession("busy-shared", "await tools.Read({}); while (true) {}", setOf("Read"))
                    .also { assertTrue(it.advance() is CodeModeStep.Calls) }
            }
            val running = cells.map { cell ->
                async {
                    try {
                        withTimeout(15_000) { cell.advance(listOf(CodeModeResult("1", "go"))) }
                    } catch (_: IOException) {
                        // Closing the addressed cell, not its whole host, releases an execution lane.
                    }
                }
            }
            withTimeout(5_000) { while (runtime.busyCells() != cells.size) yield() }
            val sibling = withTimeout(5_000) {
                runtime.startSession("independent", "return 'independent';", emptySet())
            }
            assertEquals("independent", (sibling.advance() as CodeModeStep.Completed).output)
            runtime.closeSession("busy-shared")
            withTimeout(5_000) { running.forEach { it.await() } }
            val next = withTimeout(5_000) { runtime.startSession("new", "return 'admitted';", emptySet()) }
            assertEquals("admitted", (next.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `twenty expired sessions return the live engine count to zero without spawning twenty hosts`() = runBlocking {
        val spawned = ConcurrentLinkedQueue<Process>()
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1),
            launch = HostLaunch(
                classpath = testClasspath,
                spawn = WorkerSpawn { it.start().also(spawned::add) },
            ),
        ).use { runtime ->
            repeat(20) { index ->
                val key = "fixture-$index"
                val cell = parked(runtime, key)
                assertEquals(1, runtime.liveEngines())
                assertEquals("completed", result(cell, "completed").output)
                runtime.closeSession(key)
                withTimeout(5_000) { while (runtime.liveEngines() != 0) yield() }
            }
            assertEquals(1, spawned.size)
        }
    }

    @Test
    fun `engine capacity never evicts parked cells and reclaims a completed idle session`() = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1),
            launch = HostLaunch(classpath = testClasspath),
        ).use { runtime ->
            val held = (0 until CodeModeHeap.maxEnginesPerHost).map { parked(runtime, "held-$it") }
            assertEquals(CodeModeHeap.maxEnginesPerHost, runtime.liveEngines())
            assertThrows(CodeModeCapacityException::class.java) {
                runBlocking { runtime.startSession("overflow", "return 'not executed';", emptySet()) }
            }
            assertEquals("released", result(held.first(), "released").output)
            withTimeout(5_000) { while (runtime.liveCells() != held.size - 1) yield() }
            val admitted = parked(runtime, "overflow")
            assertEquals(CodeModeHeap.maxEnginesPerHost, runtime.liveEngines())
            assertEquals("admitted", result(admitted, "admitted").output)
            held.drop(1).forEachIndexed { index, cell ->
                assertEquals("kept-$index", result(cell, "kept-$index").output)
            }
        }
    }

    @Test
    fun `a full head of empty engines evicts the oldest instead of refusing the next session`() = runBlocking {
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 2, memoryBudgetMb = 4096),
            launch = HostLaunch(classpath = testClasspath),
        ).use { runtime ->
            val first = runtime.startSession("idle-a", "return 'a';", emptySet())
            assertEquals("a", (first.advance() as CodeModeStep.Completed).output)
            withTimeout(5_000) { while (runtime.liveCells() != 0) yield() }
            val second = runtime.startSession("idle-b", "return 'b';", emptySet())
            assertEquals("b", (second.advance() as CodeModeStep.Completed).output)
            withTimeout(5_000) { while (runtime.liveCells() != 0) yield() }
            assertEquals(2, runtime.liveEngines())
            val next = withTimeout(5_000) { parked(runtime, "new") }
            assertEquals(2, runtime.liveEngines(), "the old empty engine must be closed before another is allocated")
            assertEquals("admitted", result(next, "admitted").output)
        }
    }

    @Test
    fun `head memory budget refuses the next engine before host or engine allocation`() = runBlocking {
        val spawned = ConcurrentLinkedQueue<Process>()
        val policy = CodeModePoolAdmission(DEFAULT_MAX_WORKERS, DEFAULT_HEAP_MB, DEFAULT_POOL_MEMORY_MB)
        JvmCodeModeRuntime(
            launch = HostLaunch(
                classpath = testClasspath,
                spawn = WorkerSpawn { it.start().also(spawned::add) },
            ),
        ).use { runtime ->
            val held = (0 until 21).map { parked(runtime, "budget-$it") }
            assertEquals(DEFAULT_MAX_WORKERS, spawned.size)
            assertEquals(held.size, runtime.liveEngines())
            assertTrue(policy.reservedBytes(spawned.size, held.size) <= policy.budgetBytes)
            assertTrue(policy.reservedBytes(spawned.size, held.size + 1) > policy.budgetBytes)
            val error = assertThrows(CodeModeCapacityException::class.java) {
                runBlocking { runtime.startSession("over-budget", "throw Error('must never run');", emptySet()) }
            }
            assertTrue(error.message.orEmpty().contains("24576 MiB"))
            assertTrue(error.message.orEmpty().contains("quirks.code_mode_memory_mb"))
            assertTrue(error.message.orEmpty().contains("quirks.code_mode_workers"))
            assertEquals(DEFAULT_MAX_WORKERS, spawned.size)
            assertEquals(held.size, runtime.liveEngines())
            held.forEachIndexed { index, cell -> assertEquals("ok-$index", result(cell, "ok-$index").output) }
        }
    }

    @Test
    fun `idle eviction closes empty engines but never expires a parked cell`() = runBlocking {
        val clock = AtomicLong()
        val ticks = Channel<Unit>()
        val ticker = Ticker {
            ticks.receive()
            true
        }
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1, idleTimeoutMs = 200),
            launch = HostLaunch(classpath = testClasspath),
            now = ElapsedClock(clock::get),
            ticker = ticker,
        ).use { runtime ->
            val held = parked(runtime, "parked")
            val done = runtime.startSession("idle", "return 'done';", emptySet())
            assertEquals("done", (done.advance() as CodeModeStep.Completed).output)
            withTimeout(5_000) { while (runtime.liveCells() != 1) yield() }
            clock.set(1_000)
            ticks.send(Unit)
            withTimeout(5_000) { while (runtime.liveEngines() != 1) yield() }
            clock.set(2_000)
            ticks.send(Unit)
            assertEquals(1, runtime.liveEngines(), "silence does not make a parked cell evictable")
            assertEquals("still alive", result(held, "still alive").output)
            withTimeout(5_000) { while (runtime.liveCells() != 0) yield() }
            clock.set(3_000)
            ticks.send(Unit)
            withTimeout(5_000) { while (runtime.liveEngines() != 0) yield() }
        }
        ticks.close()
        Unit
    }

    private suspend fun parked(runtime: JvmCodeModeRuntime, key: String): CodeModeCell =
        runtime.startSession(key, "return await tools.Read({});", setOf("Read")).also {
            assertTrue(it.advance() is CodeModeStep.Calls)
        }

    private suspend fun result(cell: CodeModeCell, value: String): CodeModeStep.Completed =
        cell.advance(listOf(CodeModeResult("1", value))) as CodeModeStep.Completed
}
