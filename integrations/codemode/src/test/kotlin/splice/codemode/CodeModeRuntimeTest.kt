package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeTimeoutException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

private const val REAP_WAIT_MS = 2_000L
private const val REAP_POLL_NS = 10_000_000L

class CodeModeRuntimeTest {
    private val testClasspath: String = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `Promise all resumes with original outputs in resolved call order`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start(
                source = """
                    const values = await Promise.all([
                      tools.call("Read", {file: "a"}),
                      tools.call("Read", {file: "b"})
                    ]);
                    return values.join("+");
                """.trimIndent(),
                tools = setOf("Read"),
            )
            val calls = calls(cell.advance())
            assertEquals(listOf("1", "2"), calls.map(CodeModeCall::id))
            assertEquals(listOf("a", "b"), calls.map { it.arguments["file"]?.toString()?.trim('"') })

            val completed = completed(
                cell.advance(
                    listOf(
                        CodeModeResult("2", "B"),
                        CodeModeResult("1", "A"),
                    ),
                ),
            )
            assertEquals("A+B", completed.output)
            assertEquals(null, completed.error)
        }
    }

    @Test
    fun `dependent await yields a second batch without restarting source`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start(
                source = """
                    const first = await tools.call("Read", {file: "first"});
                    const second = await tools.call("Read", {file: first});
                    return second;
                """.trimIndent(),
                tools = setOf("Read"),
            )
            assertEquals("1", calls(cell.advance()).single().id)
            val second = calls(cell.advance(listOf(CodeModeResult("1", "next")))).single()
            assertEquals("2", second.id)
            assertEquals("next", second.arguments["file"]?.toString()?.trim('"'))
            assertEquals("done", completed(cell.advance(listOf(CodeModeResult("2", "done")))).output)
        }
    }

    @Test
    fun `approved catalog may exceed execution call limit`() = runBlocking {
        runtime().use { runtime ->
            val tools = (1..192).map { index -> "Read$index" }.toSet()
            val cell = runtime.start("return \"catalog accepted\";", tools)
            assertEquals("catalog accepted", completed(cell.advance()).output)
        }
    }

    @Test
    fun `worker denies host lookup and exposes no common runtime APIs`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start(
                """
                    let hostLookup;
                    try { Java.type("java.lang.String"); hostLookup = "opened"; }
                    catch (_) { hostLookup = "denied"; }
                    return [typeof Java, typeof Packages, typeof fetch, typeof require, typeof process].join(",")
                      + "|" + hostLookup;
                """.trimIndent(),
                emptySet(),
            )
            val parts = completed(cell.advance()).output.split("|")
            assertEquals("denied", parts[1])
            assertEquals(listOf("undefined", "undefined", "undefined"), parts[0].split(",").takeLast(3))
        }
    }

    @Test
    @Timeout(3)
    fun `worker protocol fault is fatal rather than a completed guest error`() {
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            testClasspath,
            CodeModeWorker::class.java.name,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            DataOutputStream(process.outputStream.buffered()).use { input ->
                CodeModeWire.write(input, buildJsonObject { put("type", "start") })
            }
            val reply = DataInputStream(process.inputStream.buffered()).use { output ->
                // V4-226: a worker's first frame is its ready; the fault answers the start that follows.
                CodeModeFrames.parseReady(CodeModeWire.read(output))
                CodeModeWire.read(output)
            }
            assertEquals(setOf("type", "category", "faultClass"), reply.keys)
            val fault = assertThrows(CodeModeInfrastructureException::class.java) {
                CodeModeFrames.parseReply(reply, emptySet(), 1)
            }
            assertEquals(CodeModeInfrastructureCategory.PROTOCOL, fault.category)
            assertEquals(CodeModeInfrastructureClass.IO, fault.faultClass)
            assertTrue(process.waitFor(1, TimeUnit.SECONDS), "fatal worker must exit and release its process")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `malformed fatal diagnostics fail closed`() {
        val malformedFrames = listOf(
            buildJsonObject {
                put("type", "fatal")
                put("category", "UNKNOWN")
                put("faultClass", "IO")
            },
            buildJsonObject {
                put("type", "fatal")
                put("category", "PROTOCOL")
                put("faultClass", "IO")
                put("extra", "unexpected")
            },
        )

        malformedFrames.forEach { frame ->
            val error = assertThrows(IOException::class.java) {
                CodeModeFrames.parseReply(frame, emptySet(), 1)
            }
            assertTrue(error !is CodeModeInfrastructureException)
        }
    }

    @Test
    fun `duplicate client result ids fail closed`() = runBlocking {
        runtime().use { runtime ->
            val cell = twoCalls(runtime)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    cell.advance(listOf(CodeModeResult("1", "a"), CodeModeResult("1", "b")))
                }
            }
            assertClosed(cell)
        }
    }

    @Test
    fun `missing client result ids fail closed`() = runBlocking {
        runtime().use { runtime ->
            val cell = twoCalls(runtime)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    cell.advance(listOf(CodeModeResult("1", "a")))
                }
            }
            assertClosed(cell)
        }
    }

    @Test
    fun `oversized client result fails closed`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("await tools.call(\"Read\", {});", setOf("Read"))
            calls(cell.advance())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { cell.advance(listOf(CodeModeResult("1", "x".repeat(65537)))) }
            }
            assertClosed(cell)
        }
    }

    @Test
    fun `active worker cap rejects a second live cell`() = runBlocking {
        JvmCodeModeRuntime(maxWorkers = 1, workerClasspath = testClasspath).use { runtime ->
            val reclamation = CodeModeWorkerReclamation(this)
            val first = runtime.start("await tools.call(\"Read\", {});", setOf("Read"))
            assertThrows(IOException::class.java) {
                runBlocking { runtime.start("return \"second\";", emptySet()) }
            }
            first.close()
            // close() destroys the worker; its permit returns only once the exit is observed
            // (WorkerPermit), so a replacement started at once raced that observer under load.
            reclamation.assertReclaimed(runtime)
            val replacement = runtime.start("return \"replacement\";", emptySet())
            assertEquals("replacement", completed(replacement.advance()).output)
        }
    }

    // A hang guard only: the worker's start, warm-up included, is outside the deadline under test.
    @Test
    @Timeout(30)
    fun `runaway source times out and releases its worker slot`() = runBlocking {
        // Twenty times a cold worker's first yield (98-133 ms measured 2026-09-25), which this deadline
        // also covers, with the same 1 s of slack the bound always had.
        val deadlineMs = 2_000L
        JvmCodeModeRuntime(
            maxWorkers = 1,
            advanceTimeoutMs = deadlineMs,
            workerClasspath = testClasspath,
        ).use { runtime ->
            val reclamation = CodeModeWorkerReclamation(this)
            // V4-226: the deadline times the script, never its worker JVM's start, so the clock starts
            // once the script is running: it yields a tool call, then runs away on the answer. Timed
            // from before start() it also counted the boot, and a loaded runner's boot alone broke the
            // bound (gate run 36180689372).
            val cell = runtime.start("await tools.call(\"Read\", {}); while (true) {}", setOf("Read"))
            calls(cell.advance())
            val startedAt = System.nanoTime()
            val timeout = assertThrows(IOException::class.java) {
                runBlocking { cell.advance(listOf(CodeModeResult("1", "{}"))) }
            }
            assertEquals("Code-mode worker timed out", timeout.message)
            assertTrue(timeout is CodeModeTimeoutException)
            assertEquals(deadlineMs, (timeout as CodeModeTimeoutException).timeoutMillis)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < deadlineMs + 1_000)
            assertTrue(timeout.cause !is CancellationException)

            reclamation.assertReclaimed(runtime)
        }
    }

    @Test
    @Timeout(10)
    fun `cancelling startup reaps the worker and has its permit back when the cancel returns`() = runBlocking {
        // V4-214: the permit came back on the process's async onExit callback, so a start() made the
        // moment the cancel returned could still find capacity 0 (the coverage job's race). Every such
        // callback is held here until the end: only the cancelled start() itself can return it.
        val spawn = HeldExitSpawn()
        JvmCodeModeRuntime(
            maxWorkers = 1,
            advanceTimeoutMs = 5_000,
            workerClasspath = testClasspath,
            spawn = spawn,
        ).use { runtime ->
            try {
                val startup = async { runtime.start("while (true) {}", emptySet()) }
                val worker = spawn.first.await()
                worker.frameSent.await() // the start frame is out: start() is waiting on its reply
                startup.cancelAndJoin()

                val replacement = runtime.start("return \"reaped\";", emptySet())
                assertEquals("reaped", completed(replacement.advance()).output)
                val child = worker.toHandle()
                child.onExit().get(1, TimeUnit.SECONDS)
                assertTrue(reaped(child), "cancelled startup must reap the observed child")
            } finally {
                spawn.releaseExits()
            }
        }
    }

    @Test
    fun `runtime close reaps a worker still waiting for its initial reply`() = runBlocking {
        val runtime = runtime()
        val before = ProcessHandle.current().children().use { children -> children.map { it.pid() }.toList() }
        val startup = async {
            try {
                runtime.start("while (true) {}", emptySet())
            } catch (_: IOException) {
                null
            }
        }
        try {
            val child = withTimeout(2_000) {
                var spawned: ProcessHandle? = null
                while (spawned == null) {
                    yield()
                    spawned = ProcessHandle.current().children().use { children ->
                        children.filter { it.pid() !in before }.findFirst().orElse(null)
                    }
                }
                spawned
            }
            runtime.close()
            child.onExit().get(1, TimeUnit.SECONDS)
            assertTrue(reaped(child))
        } finally {
            startup.cancelAndJoin()
            runtime.close()
        }
    }

    @Test
    fun `close releases the cell when protocol output close fails`() {
        var inputClosed = false
        var released = false
        val process = TestProcess()
        val channel = WorkerChannel(
            process = process,
            input = DataInputStream(object : InputStream() {
                override fun read(): Int = -1

                override fun close() {
                    inputClosed = true
                }
            }),
            output = DataOutputStream(object : OutputStream() {
                override fun write(value: Int) = Unit

                override fun close() {
                    throw IOException("synthetic output close failure")
                }
            }),
            ioDispatcher = Dispatchers.IO,
            timeoutMs = 1_000,
        )
        val cell = JvmCodeModeCell(
            channel = channel,
            initial = WorkerReply(calls = null, output = "", error = null),
            tools = emptySet(),
            onClose = ReleaseCodeModeCell { released = true },
        )

        cell.close()

        assertTrue(process.destroyed)
        assertTrue(inputClosed)
        assertTrue(released)
    }

    @Test
    fun `a timed out reap retains capacity and cleanup until actual exit`() {
        val permits = Semaphore(0)
        val permit = WorkerPermit(permits)
        val process = TestProcess(exitOnDestroy = false)
        permit.observe(process)
        val channel = WorkerChannel(
            process = process,
            ioDispatcher = Dispatchers.IO,
            timeoutMs = 1_000,
            onExit = permit::releaseAfterExit,
        )
        var released = 0
        val cell = JvmCodeModeCell(
            channel = channel,
            initial = WorkerReply(calls = null, output = "", error = null),
            tools = emptySet(),
            onClose = ReleaseCodeModeCell { released += 1 },
        )

        cell.close()
        permit.releaseIfUnstarted()
        assertTrue(process.destroyed)
        assertEquals(0, permits.availablePermits())
        assertEquals(0, released)

        process.completeExit()
        assertEquals(1, permits.availablePermits())
        assertEquals(1, released)
        cell.close()
        process.completeExit()
        assertEquals(1, permits.availablePermits())
        assertEquals(1, released)
    }

    @Test
    fun `failure before process creation releases capacity once`() {
        val permits = Semaphore(0)
        val permit = WorkerPermit(permits)
        permit.releaseIfUnstarted()
        permit.releaseIfUnstarted()
        assertEquals(1, permits.availablePermits())
    }

    @Test
    fun `cells do not share JavaScript globals`() = runBlocking {
        runtime().use { runtime ->
            val first = runtime.start("globalThis.cellSecret = \"one\"; return \"set\";", emptySet())
            assertEquals("set", completed(first.advance()).output)
            val second = runtime.start("return typeof globalThis.cellSecret;", emptySet())
            assertEquals("undefined", completed(second.advance()).output)
        }
    }

    @Test
    fun `runtime close tolerates a registry emptying after its size was observed`() {
        for (name in listOf("cells", "starting")) {
            val runtime = runtime()
            // Deterministically model ConcurrentHashMap's weakly consistent size/iterator pair.
            val disappearing = object : AbstractMutableSet<Any>() {
                override val size: Int get() = 1

                override fun iterator(): MutableIterator<Any> = mutableSetOf<Any>().iterator()

                override fun add(element: Any): Boolean = error("fixture is read-only")
            }
            val field = JvmCodeModeRuntime::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(runtime, disappearing)

            runtime.close()
        }
    }

    // THE `<Unit>` IS LOAD-BEARING, NOT STYLE — do not tidy it away. This body ends in
    // assertThrows(...), which RETURNS the exception it checked for, so without the explicit type
    // argument runBlocking returns that exception and JUnit never discovers the method: no
    // failure, no skip, no warning. Found by checks/config/tests-are-discovered.py (V4-68), which
    // compares declared test methods against the JUnit XML — the XML read 23 against 24 declared.
    @Test
    fun `closed runtime rejects new cells`() = runBlocking<Unit> {
        val runtime = runtime()
        runtime.close()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { runtime.start("return \"never\";", emptySet()) }
        }
    }

    @Test
    fun `closed cell rejects any further advance`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("await tools.call(\"Read\", {});", setOf("Read"))
            calls(cell.advance())
            cell.close()
            assertClosed(cell)
        }
    }

    private fun runtime(): JvmCodeModeRuntime = JvmCodeModeRuntime(workerClasspath = testClasspath)

    /** Whether [child], whose exit onExit() already reported, is gone from the process table. The
     *  handle came from children(), so its onExit can be a NON-reaping wait (waitid WEXITED|WNOWAIT,
     *  ProcessHandleImpl_unix.c) registered before builder.start() returned and the Process installed
     *  its own reaping one: it completes while the worker is still a zombie, and Linux isAlive reads a
     *  zombie as alive because os_getParentPidAndTimings skips /proc/<pid>/stat's state field. The
     *  JDK's reaper collects it within milliseconds; PR #181's gate (run 35925066310) caught the
     *  window. A worker close never killed still fails, and first: the 1-second onExit wait times out.
     *  The loop polls the condition under a deadline; parkNanos is its backoff (kt-tests-no-wall-clock). */
    private fun reaped(child: ProcessHandle): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REAP_WAIT_MS)
        while (child.isAlive && System.nanoTime() < deadline) LockSupport.parkNanos(REAP_POLL_NS)
        return !child.isAlive
    }

    private suspend fun twoCalls(runtime: JvmCodeModeRuntime) = runtime.start(
        """
            await Promise.all([
              tools.call("Read", {file: "a"}),
              tools.call("Read", {file: "b"})
            ]);
        """.trimIndent(),
        setOf("Read"),
    ).also { cell -> calls(cell.advance()) }

    private fun calls(step: CodeModeStep): List<CodeModeCall> {
        assertTrue(step is CodeModeStep.Calls)
        return (step as CodeModeStep.Calls).calls
    }

    private fun completed(step: CodeModeStep): CodeModeStep.Completed {
        assertTrue(step is CodeModeStep.Completed)
        return step as CodeModeStep.Completed
    }

    private fun assertClosed(cell: splice.upstream.codemode.CodeModeCell) {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { cell.advance() }
        }
    }

    private class TestProcess(private val exitOnDestroy: Boolean = true) : Process() {
        var destroyed: Boolean = false
        private val exit = CompletableFuture<Process>()

        fun completeExit() {
            exit.complete(this)
        }

        override fun onExit(): CompletableFuture<Process> = exit

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int {
            exit.get()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exit.isDone

        override fun exitValue(): Int {
            if (!exit.isDone) throw IllegalThreadStateException("Process has not exited")
            return 0
        }

        override fun destroy() {
            destroyed = true
            if (exitOnDestroy) completeExit()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = !exit.isDone
    }

    /** Spawns the real worker, but holds every async exit observer (each onExit() future the runtime
     *  chains a callback on) until [releaseExits] — the JDK's reaper running as late as it likes. */
    private class HeldExitSpawn : WorkerSpawn {
        val first = CompletableFuture<HeldExitProcess>()
        private val exits = CompletableFuture<Unit>()

        override fun invoke(builder: ProcessBuilder): Process =
            HeldExitProcess(builder.start(), exits).also { first.complete(it) }

        fun releaseExits() {
            exits.complete(Unit)
        }
    }

    /** A real worker whose onExit() completes only once [exits] does, and which reports [frameSent]
     *  when the runtime first flushes a frame to its stdin: start() is then waiting in exchange. */
    private class HeldExitProcess(
        private val real: Process,
        private val exits: CompletableFuture<Unit>,
    ) : Process() {
        val frameSent = CompletableFuture<Unit>()
        private val stdin = object : FilterOutputStream(real.outputStream) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                out.write(bytes, offset, length)
            }

            override fun flush() {
                out.flush()
                frameSent.complete(Unit)
            }
        }

        override fun onExit(): CompletableFuture<Process> = real.onExit().thenCombine(exits) { _, _ -> this }

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = real.inputStream

        override fun getErrorStream(): InputStream = real.errorStream

        override fun waitFor(): Int = real.waitFor()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = real.waitFor(timeout, unit)

        override fun exitValue(): Int = real.exitValue()

        override fun destroy() {
            real.destroy()
        }

        override fun destroyForcibly(): Process {
            real.destroyForcibly()
            return this
        }

        override fun isAlive(): Boolean = real.isAlive

        override fun pid(): Long = real.pid()

        override fun toHandle(): ProcessHandle = real.toHandle()
    }
}
