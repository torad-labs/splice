// NEW: V4-226 — a code-mode script's deadline is the script's, never the worker JVM's start.
package splice.codemode

import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeTimeoutException
import java.io.IOException

// A start slower than the product's advance deadline: the class the gate met under a parallel build,
// where the first exchange of every cell (JVM boot, JavaScript engine, script) reached 4.9-5.2 s.
private const val SLOW_START_MS: Long = DEFAULT_ADVANCE_TIMEOUT_MS + 3_000

class CodeModeWorkerBootTest {
    private val testClasspath: String = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    @Timeout(60)
    fun `a worker that starts slower than the advance deadline still runs its script`() = runBlocking {
        JvmCodeModeRuntime(workerClasspath = testClasspath, spawn = SlowStartSpawn(SLOW_START_MS)).use { runtime ->
            val cell = runtime.start("return 6 * 7;", emptySet())
            val completed = cell.advance() as? CodeModeStep.Completed
                ?: error("the script should have completed in its first advance")
            assertEquals("42", completed.output)
            assertEquals(null, completed.error)
        }
    }

    @Test
    @Timeout(60)
    fun `a long execution is not killed by the former advance deadline`() = runBlocking {
        JvmCodeModeRuntime(advanceTimeoutMs = 1, workerClasspath = testClasspath).use { runtime ->
            val cell = runtime.start(
                "const until = Date.now() + 100; while (Date.now() < until) {} return 42;",
                emptySet(),
            )
            assertEquals("42", (cell.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    @Timeout(60)
    fun `a failed host boot is reaped and a later start boots a healthy replacement`() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.CompletableFuture<Process>()
        JvmCodeModeRuntime(
            workerClasspath = testClasspath,
            spawn = WorkerSpawn { builder ->
                if (attempts.incrementAndGet() == 1) {
                    SlowStartSpawn(NEVER_STARTS_MS)(builder).also { failed.complete(it) }
                } else {
                    builder.start()
                }
            },
            workerStartTimeoutMs = START_BUDGET_MS,
        ).use { runtime ->
            val timeout = assertThrows(CodeModeTimeoutException::class.java) {
                runBlocking { runtime.start("return 1;", emptySet()) }
            }
            assertEquals(START_BUDGET_MS, timeout.timeoutMillis)
            assertTrue(failed.get().waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "failed boot must be reaped")
            val next = withTimeout(10_000) { runtime.start("return 'replacement';", emptySet()) }
            assertEquals("replacement", (next.advance() as CodeModeStep.Completed).output)
            assertEquals(2, attempts.get())
        }
    }

    @Test
    @Timeout(60)
    fun `concurrent retries after a failed boot elect exactly one replacement host`() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val replacementEntered = java.util.concurrent.CompletableFuture<Unit>()
        val releaseReplacement = java.util.concurrent.CountDownLatch(1)
        JvmCodeModeRuntime(
            workerClasspath = testClasspath,
            workerStartTimeoutMs = START_BUDGET_MS,
            spawn = WorkerSpawn { builder ->
                if (attempts.incrementAndGet() == 1) {
                    SlowStartSpawn(NEVER_STARTS_MS)(builder)
                } else {
                    replacementEntered.complete(Unit)
                    check(releaseReplacement.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    builder.start()
                }
            },
        ).use { runtime ->
            assertThrows(CodeModeTimeoutException::class.java) {
                runBlocking { runtime.start("return 1;", emptySet()) }
            }
            kotlinx.coroutines.supervisorScope {
                val first = async { runtime.start("return 'replacement';", emptySet()) }
                withTimeout(5_000) { replacementEntered.await() }
                val followers = List(10) {
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        runtime.start("return 'replacement';", emptySet())
                    }
                }
                assertEquals(2, attempts.get(), "all callers must await the same boot")
                releaseReplacement.countDown()
                (followers + first).forEach {
                    val completed = it.await().advance() as CodeModeStep.Completed
                    assertEquals("replacement", completed.output)
                }
            }
        }
    }

    @Test
    fun `a worker's first frame is ready or a failed start, never an early answer`() {
        CodeModeFrames.parseReady(CodeModeWire.readyFrame())
        assertThrows(CodeModeInfrastructureException::class.java) {
            CodeModeFrames.parseReady(
                CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, CodeModeInfrastructureClass.RUNTIME),
            )
        }
        val early = assertThrows(IOException::class.java) {
            CodeModeFrames.parseReady(CodeModeWire.completedFrame("42", null))
        }
        assertEquals("Code-mode worker answered before it was ready", early.message)
        assertThrows(IOException::class.java) {
            CodeModeFrames.parseReady(
                buildJsonObject {
                    put("type", "ready")
                    put("extra", true)
                },
            )
        }
    }
}

// A start budget far below the default, and a worker that would come up only well after it.
private const val START_BUDGET_MS: Long = 2_000
private const val NEVER_STARTS_MS: Long = 8_000

/** Spawns the real worker behind a shell that waits [delayMs] first: the process exists, and the
 *  parent's clock runs, while the worker has not started. /bin/sh and /bin/sleep by path, because the
 *  runtime hands its worker an empty environment. */
internal class SlowStartSpawn(private val delayMs: Long) : WorkerSpawn {
    override fun invoke(builder: ProcessBuilder): Process {
        val seconds = "%.3f".format(java.util.Locale.ROOT, delayMs / 1_000.0)
        builder.command(listOf("/bin/sh", "-c", "/bin/sleep $seconds; exec \"\$0\" \"\$@\"") + builder.command())
        return builder.start()
    }
}
