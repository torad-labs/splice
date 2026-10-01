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
import org.junit.jupiter.api.io.TempDir
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeTimeoutException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

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
            val timeout = assertThrows(CodeModeStartException::class.java) {
                runBlocking { runtime.start("return 1;", emptySet()) }
            }
            assertEquals(START_BUDGET_MS, (timeout.cause as CodeModeTimeoutException).timeoutMillis)
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
            assertThrows(CodeModeStartException::class.java) {
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
    @Timeout(60)
    fun `a replacement host uses the daemon archive after the installed path is swapped`(
        @TempDir root: Path,
    ) = runBlocking {
        val archive = SwappedWorkerArchive(root, testClasspath)
        val processes = CopyOnWriteArrayList<Process>()
        val hashes = CopyOnWriteArrayList<String>()
        val originalHash = archive.hash(archive.jar)
        JvmCodeModeRuntime(
            workerClasspath = archive.classpath,
            spawn = WorkerSpawn { builder ->
                val pinned = Path.of(builder.command()[3].substringBefore(File.pathSeparator))
                hashes.add(archive.hash(pinned))
                builder.redirectError(ProcessBuilder.Redirect.INHERIT).start().also { processes.add(it) }
            },
        ).use { runtime ->
            val parked = runtime.start("return await tools.call('Read', {});", setOf("Read"))
            assertTrue(parked.advance() is CodeModeStep.Calls)
            archive.replace()
            processes.single().destroyForcibly().waitFor()
            assertThrows(CodeModeWorkerLostException::class.java) {
                runBlocking { parked.advance(listOf(CodeModeResult("1", "unused"))) }
            }
            val replacement = runtime.start("return 'original protocol';", emptySet())
            val completed = replacement.advance() as CodeModeStep.Completed
            assertEquals("original protocol", completed.output)
            assertEquals(null, completed.error)
            assertEquals(2, processes.size)
            assertEquals(listOf(originalHash, originalHash), hashes)
        }
    }

    @Test
    @Timeout(60)
    fun `an install before first runtime refuses a mismatched daemon archive before spawn`(
        @TempDir root: Path,
    ) {
        val archive = SwappedWorkerArchive(root, testClasspath)
        val probeClasses = Path.of(ArchiveBootProbe::class.java.protectionDomain.codeSource.location.toURI())
        val classpath = archive.classpath + File.pathSeparator + probeClasses
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            ArchiveBootProbe::class.java.name,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            process.inputStream.bufferedReader().use { replies ->
                assertEquals("loaded original", replies.readLine())
                archive.replaceWorkerClass()
                process.outputStream.write(1)
                process.outputStream.flush()
                assertEquals("refused before spawn", replies.readLine())
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            }
        } finally {
            process.destroyForcibly().waitFor()
        }
    }

    @Test
    @Timeout(60)
    fun `boot capture precedes first runtime and survives an install before the first script`(
        @TempDir root: Path,
    ) {
        val archive = SwappedWorkerArchive(root, testClasspath)
        val probeClasses = Path.of(ArchiveBootProbe::class.java.protectionDomain.codeSource.location.toURI())
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            archive.classpath + File.pathSeparator + probeClasses,
            ArchiveBootProbe::class.java.name,
            "eager",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            process.inputStream.bufferedReader().use { replies ->
                assertEquals("loaded original", replies.readLine())
                archive.replaceWorkerClass()
                process.outputStream.write(1)
                process.outputStream.flush()
                assertEquals("ran original archive", replies.readLine())
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            }
        } finally {
            process.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `worker archive identity is pinned independently of its filename extension`(@TempDir root: Path) {
        for (name in listOf("worker.JAR", "worker.zip", "worker.archive")) {
            val archive = SwappedWorkerArchive(root, testClasspath, name)
            val pinned = Path.of(WorkerArtifacts.pinClasspath(archive.classpath).substringBefore(File.pathSeparator))
            assertTrue(pinned != archive.jar)
            assertEquals(archive.hash(archive.jar), archive.hash(pinned))
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
