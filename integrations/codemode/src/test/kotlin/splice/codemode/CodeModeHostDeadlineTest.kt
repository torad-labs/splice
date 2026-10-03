// NEW: caller deadlines abandon waits, not independently timed host control exchanges or boot generations.
package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.host.CodeModeHostBoots
import splice.codemode.host.CodeModeHostDrains
import splice.codemode.host.CodeModeHostMetrics
import splice.codemode.host.CodeModeHostPool
import splice.codemode.host.CodeModeHostStart
import splice.codemode.host.CodeModePoolAdmission
import splice.codemode.host.CodeModePoolHost
import splice.codemode.host.CodeModePoolLease
import splice.codemode.host.CodeModePoolSession
import splice.codemode.host.CodeModePoolTimes
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.upstream.LifecycleScope
import splice.upstream.Ticker
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@OptIn(ExperimentalCoroutinesApi::class, InternalForInheritanceCoroutinesApi::class)
@Timeout(30)
class CodeModeHostDeadlineTest {
    @Test
    fun `a caller deadline after slow boot keeps its independently timed engine open alive`() = runTest {
        val io = LifecycleScope(ProcessDispatchers().io())
        val process = SilentHostCloseProcess().also { it.holdEngineOpen = true }
        process.holdOpenWrite = true
        val channel = SharedWorkerChannel(process, io)
        val pool = CodeModeHostPool(
            CodeModePoolAdmission(1, DEFAULT_HEAP_MB, DEFAULT_POOL_MEMORY_MB),
            backgroundScope,
            CodeModeHostStart {
                delay(25_000)
                channel
            },
            ElapsedClock { testScheduler.currentTime },
            Ticker { false },
            CodeModePoolTimes(60_000, 30_000),
            LogSink {},
        )
        try {
            runBlocking { channel.awaitReady() }
            val caller = async {
                try {
                    withTimeout(30_000) { pool.open("deadline-fixture") }
                    error("the held engine must outlast the caller's deadline")
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    error
                }
            }
            runCurrent()
            advanceTimeBy(25_000)
            runCurrent()
            assertTrue(process.openEntered.await(5, TimeUnit.SECONDS), "the engine open never reached the host")
            advanceTimeBy(5_000)
            runCurrent()
            caller.await()
            assertTrue(process.isAlive, "the caller deadline must not quarantine a five-second-old engine open")
            val retry = async(start = CoroutineStart.UNDISPATCHED) { pool.open("deadline-fixture") }
            assertFalse(retry.isCompleted, "the retry must await the original open, not return an uninitialized lease")
            assertEquals(1, process.opens.get(), "a retry must not send a duplicate engine-open exchange")
            advanceTimeBy(3_000)
            process.releaseOpen()
            awaitRetry(this, retry)
            assertEquals(1, process.opens.get(), "a retry must join the same engine-open exchange")
            pool.release(retry.await())
        } finally {
            pool.close()
            channel.close()
            io.cancel()
        }
    }

    /** Pump only current virtual work while the real IO thread publishes its reply and write continuation. */
    private fun awaitRetry(scope: TestScope, retry: Deferred<CodeModePoolLease>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!retry.isCompleted && System.nanoTime() < deadline) {
            scope.runCurrent()
            Thread.yield()
        }
        assertTrue(retry.isCompleted, "the retry must complete without advancing the engine control deadline")
    }

    @Test
    fun `a failed boot is replaced before its asynchronous observer obtains the placement lock`() = runBlocking {
        val scope = LifecycleScope(ProcessDispatchers().io())
        val attempts = AtomicInteger()
        val host = CodeModePoolHost()
        val lock = ReentrantLock()
        SharedWorkerChannel(SilentHostCloseProcess(), scope).use { channel ->
            val boots = CodeModeHostBoots(
                scope,
                CodeModeHostStart {
                    if (attempts.incrementAndGet() == 1) throw IOException("synthetic boot failure")
                    channel
                },
                lock,
            )
            try {
                lock.withLock {
                    val first = boots.open(host)
                    try {
                        runBlocking { first.await() }
                        error("the first boot must fail")
                    } catch (_: IOException) {
                        // Its observer is blocked by this test's placement lock.
                    }
                    val replacement = boots.open(host)
                    assertNotSame(first, replacement, "a failed generation must not be returned to its retry")
                    assertTrue(List(10) { boots.open(host) }.all { it === replacement })
                    assertTrue(runBlocking { replacement.await() } === channel)
                }
                assertEquals(2, attempts.get(), "concurrent retries must elect only one replacement")
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `a closed cached host is replaced before its exit notification is published`() = runBlocking {
        val scope = LifecycleScope(ProcessDispatchers().io())
        val ended = AtomicBoolean()
        val previousProcess = DelayedHostExitProcess()
        val nextProcess = SilentHostCloseProcess()
        val previous = SharedWorkerChannel(previousProcess, scope)
        val next = SharedWorkerChannel(nextProcess, scope)
        val attempts = AtomicInteger()
        val pool = CodeModeHostPool(
            CodeModePoolAdmission(1, DEFAULT_HEAP_MB, DEFAULT_POOL_MEMORY_MB),
            scope,
            CodeModeHostStart { if (attempts.incrementAndGet() == 1) previous else next },
            ElapsedClock { 0L },
            Ticker { false },
            CodeModePoolTimes(60_000, 30_000),
            LogSink {},
        )
        try {
            previous.awaitReady()
            next.awaitReady()
            previous.afterExit { ended.set(true) }
            pool.release(pool.open("closed-before-exit"))
            previous.close()
            assertTrue(previous.isClosed)
            assertFalse(ended.get(), "the process exit notification is deliberately still withheld")
            pool.release(pool.open("closed-before-exit"))
            assertEquals(2, attempts.get(), "the start must replace the already closed cached channel")
            assertEquals(1, nextProcess.opens.get())
            previousProcess.publishExit()
            assertTrue(ended.get())
            pool.release(pool.open("closed-before-exit"))
            assertEquals(2, attempts.get(), "a late old exit must not clear the replacement generation")
            assertEquals(1, nextProcess.opens.get(), "the initialized replacement engine is reused")
        } finally {
            previousProcess.publishExit()
            pool.close()
            previous.close()
            next.close()
            scope.cancel()
        }
    }

    @Test
    fun `a confirmed failed boot can be replaced before its old stream cleanup finishes`() = runBlocking {
        val process = HeldBootCleanupProcess()
        val attempts = AtomicInteger()
        val messages = java.util.concurrent.ConcurrentLinkedQueue<String>()
        JvmCodeModeRuntime(
            maxWorkers = 1,
            workerStartTimeoutMs = 300,
            spawn = WorkerSpawn {
                if (attempts.incrementAndGet() == 1) process else SilentHostCloseProcess()
            },
        ).also { it.observeHostLifecycle(LogSink(messages::add)) }.use { runtime ->
            try {
                assertThrows(CodeModeStartException::class.java) {
                    runBlocking { runtime.start("return 'never';", emptySet()) }
                }
                assertTrue(process.closing.await(5, TimeUnit.SECONDS), "boot cleanup never reached its held stream")
                assertFalse(process.isAlive, "the failed host is already confirmed dead")
                HostLifecycleAwait.ended(messages)
                val replacement = withTimeout(5_000) { runtime.start("return 'replacement';", emptySet()) }
                assertEquals(2, attempts.get(), "confirmed process death must unblock a fresh boot")
                assertEquals("fixture", (replacement.advance() as CodeModeStep.Completed).output)
            } finally {
                process.release.countDown()
            }
        }
    }

    @Test
    fun `a late old admission cannot attach its engine open after the generation was cleared`() = runBlocking {
        val scope = LifecycleScope(ProcessDispatchers().io())
        val process = SilentHostCloseProcess()
        SharedWorkerChannel(process, scope).use { channel ->
            val host = CodeModePoolHost()
            val session = CodeModePoolSession("cleared-fixture", 1, host)
            val drains = CodeModeHostDrains(
                scope,
                ReentrantLock(),
                CodeModePoolAdmission(1, DEFAULT_HEAP_MB, DEFAULT_POOL_MEMORY_MB),
                LogSink {},
            )
            try {
                channel.awaitReady()
                host.boot = CompletableDeferred(channel)
                channel.close()
                host.boot = null
                try {
                    drains.admit(session, channel, CodeModeHostMetrics())
                    error("an old channel cannot admit into its cleared host generation")
                } catch (_: CodeModeWorkerLostException) {
                    // The host exit observer cleared placement before this old caller reached admission.
                }
                assertEquals(0, process.opens.get(), "the cleared generation must reject before sending an open")
                assertTrue(session.opening == null, "a late old caller must not poison the next generation's open")
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `an exit observer racing an opener cannot leave a stale admission attached after clearing`() = runBlocking {
        val scope = LifecycleScope(ProcessDispatchers().io())
        SharedWorkerChannel(SilentHostCloseProcess(), scope).use { channel ->
            val lock = ReentrantLock(true)
            val host = CodeModePoolHost()
            val session = CodeModePoolSession("race-fixture", 1, host)
            val observer = Thread {
                lock.withLock {
                    host.boot = null
                    session.opening = null
                    session.initialized = false
                }
            }.apply { isDaemon = true }
            val completed = CompletableDeferred(channel)
            val once = AtomicBoolean()
            host.boot = object : Deferred<SharedWorkerChannel> by completed {
                override fun getCompleted(): SharedWorkerChannel {
                    if (once.compareAndSet(false, true)) {
                        observer.start()
                        waitForPlacement(observer)
                    }
                    return completed.getCompleted()
                }
            }
            val drains = CodeModeHostDrains(
                scope,
                lock,
                CodeModePoolAdmission(1, DEFAULT_HEAP_MB, DEFAULT_POOL_MEMORY_MB),
                LogSink {},
            )
            try {
                channel.awaitReady()
                try {
                    drains.admit(session, channel, CodeModeHostMetrics())
                } catch (_: CodeModeWorkerLostException) {
                    // The exit observer may win before the independently dispatched open completes.
                }
                observer.join(5_000)
                assertEquals(Thread.State.TERMINATED, observer.state, "the exit observer must have run")
                assertFalse(observer.isAlive)
                assertNull(session.opening, "checking and publishing admission must be one placement-lock region")
                assertFalse(session.initialized, "an exited generation must not initialize the cleared session")
            } finally {
                scope.cancel()
            }
        }
    }

    private fun waitForPlacement(observer: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (observer.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals(Thread.State.WAITING, observer.state, "the exit observer must queue behind the opener's lock")
    }

    @Test
    fun `abandoning engine admission rethrows the caller's original cancellation`() = runBlocking {
        val scope = LifecycleScope(ProcessDispatchers().io())
        val process = SilentHostCloseProcess().also { it.holdEngineOpen = true }
        SharedWorkerChannel(process, scope).use { channel ->
            val host = CodeModePoolHost().also { it.boot = CompletableDeferred(channel) }
            val session = CodeModePoolSession("cancel-fixture", 1, host)
            val drains = CodeModeHostDrains(
                scope,
                ReentrantLock(),
                CodeModePoolAdmission(1, DEFAULT_HEAP_MB, DEFAULT_POOL_MEMORY_MB),
                LogSink {},
            )
            val observed = CompletableDeferred<Throwable>()
            val cancellation = CancellationException("synthetic caller cancellation")
            try {
                channel.awaitReady()
                val caller = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        drains.admit(session, channel, CodeModeHostMetrics())
                    } catch (error: CancellationException) {
                        observed.complete(error)
                        throw error
                    } catch (error: IOException) {
                        observed.complete(error)
                    }
                }
                assertTrue(process.openEntered.await(5, TimeUnit.SECONDS))
                caller.cancel(cancellation)
                val error = withTimeout(5_000) { observed.await() }
                assertTrue(error is CancellationException, "caller cancellation must never become an I/O failure")
                assertTrue(
                    error === cancellation || error.cause === cancellation,
                    "the original cancellation must survive",
                )
                assertTrue(process.isAlive)
            } finally {
                scope.cancel()
            }
        }
    }
}

private class DelayedHostExitProcess : SilentHostCloseProcess() {
    private val published = CompletableFuture<Process>()

    override fun onExit(): CompletableFuture<Process> = published
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = published.isDone

    fun publishExit() {
        published.complete(this)
    }
}

private class HeldBootCleanupProcess : SilentHostCloseProcess() {
    val closing = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val input = object : FilterInputStream(super.getInputStream()) {
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            release.await()
            return super.read(bytes, offset, length)
        }

        override fun close() {
            closing.countDown()
            release.await()
            super.close()
        }
    }

    override fun getInputStream(): InputStream = input
}
