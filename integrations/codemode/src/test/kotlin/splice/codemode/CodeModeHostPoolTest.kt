// NEW: actual host death, guest execution and engine retirement stay isolated by session ownership.
package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.host.HostLaunch
import splice.codemode.host.PoolLimits
import splice.core.util.ElapsedClock
import splice.upstream.Ticker
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

@Timeout(90)
class CodeModeHostPoolTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

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

    // The host's contract: a code-mode host the idle sweep emptied closes its JVM, and the next turn boots a fresh one.
    @Test
    fun `an idle host closes its worker process and the next session runs on a fresh host`() = runBlocking {
        val clock = AtomicLong()
        val ticks = Channel<Unit>()
        val spawned = ConcurrentLinkedQueue<Process>()
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1, idleTimeoutMs = 200),
            launch = HostLaunch(classpath = testClasspath, spawn = WorkerSpawn { it.start().also(spawned::add) }),
            now = ElapsedClock(clock::get),
            ticker = Ticker {
                ticks.receive()
                true
            },
        ).use { runtime ->
            val done = runtime.startSession("idle", "return 'done';", emptySet())
            assertEquals("done", (done.advance() as CodeModeStep.Completed).output)
            withTimeout(5_000) { while (runtime.liveCells() != 0) yield() }
            clock.set(1_000)
            ticks.send(Unit)
            withTimeout(30_000) { while (spawned.any(Process::isAlive)) yield() }

            val next = runtime.startSession("next", "return 'fresh';", emptySet())
            assertEquals("fresh", (next.advance() as CodeModeStep.Completed).output)
            assertTrue(spawned.any(Process::isAlive), "the next session runs on a live host")
        }
        ticks.close()
        Unit
    }

    // A lease that races the sweep either keeps the host by holding a session on it or lands on a fresh one.
    @Test
    fun `a session opened while its idle host is being released still runs`() = runBlocking {
        val clock = AtomicLong()
        val ticks = Channel<Unit>()
        JvmCodeModeRuntime(
            limits = PoolLimits(maxWorkers = 1, idleTimeoutMs = 200),
            launch = HostLaunch(classpath = testClasspath),
            now = ElapsedClock(clock::get),
            ticker = Ticker {
                ticks.receive()
                true
            },
        ).use { runtime ->
            repeat(3) { round ->
                val done = runtime.startSession("idle-$round", "return 'done';", emptySet())
                assertEquals("done", (done.advance() as CodeModeStep.Completed).output)
                withTimeout(5_000) { while (runtime.liveCells() != 0) yield() }
                clock.addAndGet(1_000)
                val sweep = async { ticks.send(Unit) }
                val racing = async {
                    runtime.startSession("racing-$round", "return 'raced-$round';", emptySet()).advance()
                }
                assertEquals("raced-$round", (withTimeout(30_000) { racing.await() } as CodeModeStep.Completed).output)
                sweep.await()
            }
        }
        ticks.close()
        Unit
    }
}
