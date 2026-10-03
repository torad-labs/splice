package splice.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeStartException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
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

    /** Oct 1: nine reads under Promise.allSettled and then a Bash poisoned the cell with "invalid call id".
     *  The ninth read hit the batch cap and burned id 9, so the Bash call arrived as id 10. */
    @Test
    fun `calls past one batch wait for the next batch and keep their ids in step`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start(
                source = """
                    const reads = await Promise.allSettled(
                      Array.from({length: 9}, (_, i) => tools.call("Read", {file: "f" + i})));
                    const shell = await tools.call("Bash", {command: "ls"});
                    return reads.map(r => r.status + ":" + r.value).join(",") + "|" + shell;
                """.trimIndent(),
                tools = setOf("Read", "Bash"),
            )
            val first = calls(cell.advance())
            assertEquals((1..8).map(Int::toString), first.map(CodeModeCall::id))
            val second = calls(cell.advance(first.map { CodeModeResult(it.id, "r" + it.id) }))
            assertEquals(listOf("9"), second.map(CodeModeCall::id))
            assertEquals("Read", second.single().name)
            val third = calls(cell.advance(listOf(CodeModeResult("9", "r9"))))
            assertEquals(listOf("10" to "Bash"), third.map { it.id to it.name })
            val completed = completed(cell.advance(listOf(CodeModeResult("10", "listed"))))
            assertEquals((1..9).joinToString(",") { "fulfilled:r$it" } + "|listed", completed.output)
        }
    }

    @Test
    fun `four full batches reach the cell cap and the thirty third call is refused`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start(
                source = """
                    let refused = "";
                    for (let i = 0; i < 4; i++) {
                      await Promise.all(Array.from({length: 8}, () => tools.call("Read", {})));
                    }
                    try { await tools.call("Read", {}); } catch (e) { refused = String(e.message || e); }
                    return refused;
                """.trimIndent(),
                tools = setOf("Read"),
            )
            var step = cell.advance()
            repeat(4) { batch ->
                val calls = calls(step)
                assertEquals((batch * 8 + 1..batch * 8 + 8).map(Int::toString), calls.map(CodeModeCall::id))
                step = cell.advance(calls.map { CodeModeResult(it.id, "ok") })
            }
            assertTrue(completed(step).output.contains("call limit"), completed(step).output)
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

    // A hang guard over a worker JVM's own boot, so it sits where a loaded runner's boot fits (see
    // SCRIPT_DEADLINE_MS); at 3 s it read a slow start as a hang.
    @Test
    @Timeout(60)
    fun `worker protocol fault is fatal rather than a completed guest error`() {
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            testClasspath,
            CodeModeWorker::class.java.name,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val input = DataOutputStream(process.outputStream.buffered())
            val output = DataInputStream(process.inputStream.buffered())
            CodeModeFrames.parseReady(CodeModeWire.read(output))
            CodeModeWire.write(input, HostProtocol.frame(1, 1, buildJsonObject { put("type", "start") }))
            val reply = HostProtocol.parse(CodeModeWire.read(output)).payload
            assertEquals(setOf("type", "category", "faultClass"), reply.keys)
            val fault = assertThrows(CodeModeInfrastructureException::class.java) {
                CodeModeFrames.parseReply(reply, emptySet(), 1)
            }
            assertEquals(CodeModeInfrastructureCategory.PROTOCOL, fault.category)
            assertEquals(CodeModeInfrastructureClass.IO, fault.faultClass)
            assertTrue(process.isAlive, "a cell protocol fault must not destroy the shared host")
            CodeModeWire.write(input, HostProtocol.frame(1, 2, HostProtocol.close()))
            assertEquals(2L, HostProtocol.parse(CodeModeWire.read(output)).request)
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
    fun `one host runs a second live cell while the first stays parked`() = runBlocking {
        JvmCodeModeRuntime(maxWorkers = 1, workerClasspath = testClasspath).use { runtime ->
            val first = runtime.start("return await tools.call('Read', {});", setOf("Read"))
            calls(first.advance())
            val second = withTimeout(5_000) { runtime.start("return 'second';", emptySet()) }
            assertEquals("second", completed(second.advance()).output)
            assertEquals("first", completed(first.advance(listOf(CodeModeResult("1", "first")))).output)
        }
    }

    @Test
    @Timeout(30)
    fun `a long resumed step survives the former advance deadline`() = runBlocking {
        JvmCodeModeRuntime(advanceTimeoutMs = 1, workerClasspath = testClasspath).use { runtime ->
            val cell = runtime.start(
                "await tools.call('Read', {}); const until = Date.now() + 100; while (Date.now() < until) {} return 'done';",
                setOf("Read"),
            )
            calls(cell.advance())
            assertEquals("done", completed(cell.advance(listOf(CodeModeResult("1", "")))).output)
        }
    }

    @Test
    @Timeout(60)
    fun `cancelling startup closes only its context and reuses the same host`() = runBlocking {
        val spawn = HeldExitSpawn()
        JvmCodeModeRuntime(workerClasspath = testClasspath, spawn = spawn).use { runtime ->
            try {
                val startup = async { runtime.start("while (true) {}", emptySet()) }
                val worker = spawn.first.await()
                worker.frameSent.await()
                startup.cancelAndJoin()
                assertTrue(worker.isAlive, "a cancelled script must not destroy the shared host")
                val replacement = withTimeout(5_000) { runtime.start("return 'alive';", emptySet()) }
                assertEquals("alive", completed(replacement.advance()).output)
            } finally {
                spawn.releaseExits()
            }
        }
    }

    @Test
    fun `runtime close reaps a worker still waiting for its initial reply`() = runBlocking {
        val spawn = HeldExitSpawn()
        val runtime = JvmCodeModeRuntime(workerClasspath = testClasspath, spawn = spawn)
        val startup = async {
            try {
                runtime.start("while (true) {}", emptySet())
            } catch (_: IOException) {
                null
            }
        }
        try {
            val worker = withTimeout(5_000) { spawn.first.await() }
            withTimeout(5_000) { worker.frameSent.await() }
            runtime.close()
            val child = worker.toHandle()
            child.onExit().get(1, TimeUnit.SECONDS)
            assertTrue(reaped(child))
        } finally {
            startup.cancelAndJoin()
            spawn.releaseExits()
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
        )
        val cell = JvmCodeModeCell(
            channel = cellChannel(channel),
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
    fun `a timed out reap retains cleanup ownership until actual host exit`() {
        val process = TestProcess(exitOnDestroy = false)
        val channel = WorkerChannel(process)
        var released = 0
        channel.afterExit { released += 1 }
        channel.close()
        assertTrue(process.destroyed)
        assertEquals(0, released)
        process.completeExit()
        assertEquals(1, released)
        channel.close()
        process.completeExit()
        assertEquals(1, released)
    }

    @Test
    fun `an already exited host releases a late cleanup observer exactly once`() {
        val process = TestProcess()
        val channel = WorkerChannel(process)
        process.completeExit()
        var released = 0
        channel.afterExit { released += 1 }
        channel.close()
        process.completeExit()
        assertEquals(1, released)
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
        for (name in listOf("cells")) {
            val runtime = runtime()
            // Deterministically model ConcurrentHashMap's weakly consistent size/iterator pair.
            val disappearing = object : AbstractMutableSet<Any>() {
                override val size: Int get() = 1

                override fun iterator(): MutableIterator<Any> = mutableSetOf<Any>().iterator()

                override fun add(element: Any): Boolean = error("fixture is read-only")
            }
            val ownerField = JvmCodeModeRuntime::class.java.getDeclaredField("cells").apply { isAccessible = true }
            val owner = ownerField.get(runtime)
            val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
            field.set(owner, disappearing)

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
        val failure = assertThrows(CodeModeStartException::class.java) {
            runBlocking { runtime.start("return \"never\";", emptySet()) }
        }
        assertTrue(failure.cause is IllegalStateException)
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

    private fun runtime(): JvmCodeModeRuntime =
        JvmCodeModeRuntime(advanceTimeoutMs = SCRIPT_DEADLINE_MS, workerClasspath = testClasspath)

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
        // CodeModeCellStarts also reports the transport exit after a local close has already won.
        (cell as JvmCodeModeCell).stop()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { cell.advance() }
        }
    }

    private fun cellChannel(transport: WorkerChannel): CellChannel = object : CellChannel {
        override suspend fun exchange(
            frame: kotlinx.serialization.json.JsonObject,
        ): kotlinx.serialization.json.JsonObject =
            error("the cleanup fixture never exchanges frames")

        override fun afterExit(action: WorkerExited) = transport.afterExit(action)

        override fun close() = transport.close()
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

class CodeModeStopRaceTest {
    @Test
    @Timeout(10)
    fun `stop during a result exchange remains worker loss`() = runBlocking {
        val channel = PausedCellChannel()
        val cell = resultCell(channel)
        assertTrue(cell.advance() is CodeModeStep.Calls)
        assertStoppedExchange(cell, channel, listOf(CodeModeResult("1", "result")))
    }

    @Test
    @Timeout(10)
    fun `stop after reading source but during its exchange remains worker loss`() = runBlocking {
        val channel = PausedCellChannel()
        val cell = JvmCodeModeCell(
            channel,
            WorkerReply(null, null, null, waitingForInput = true),
            emptySet(),
            ReleaseCodeModeCell {},
            CodeModeSource { CodeModeSourcePart.Delta("text('source');") },
        )
        assertStoppedExchange(cell, channel, emptyList())
    }

    @Test
    @Timeout(10)
    fun `local close during an exchange is not relabeled worker loss`() = runBlocking<Unit> {
        listOf(null, CodeModeWorkerLostException()).forEach { laterFailure ->
            supervisorScope {
                val channel = PausedCellChannel().apply { exchangeFailure = laterFailure }
                val cell = resultCell(channel)
                cell.advance()
                val advancing = async { cell.advance(listOf(CodeModeResult("1", "result"))) }
                channel.entered.await()
                cell.close()
                channel.proceed.complete(Unit)
                assertThrows(IllegalStateException::class.java) { runBlocking { advancing.await() } }
                assertEquals(1, channel.closes)
            }
        }
    }

    @Test
    @Timeout(10)
    fun `exchange cancellation is preserved even when a stop has won`() = runBlocking<Unit> {
        supervisorScope {
            val cancelled = CancellationException("synthetic exchange cancellation")
            val channel = PausedCellChannel().apply { exchangeFailure = cancelled }
            val cell = resultCell(channel)
            cell.advance()
            val advancing = async { cell.advance(listOf(CodeModeResult("1", "result"))) }
            channel.entered.await()
            cell.stop()
            channel.proceed.complete(Unit)
            val failure = assertThrows(CancellationException::class.java) { runBlocking { advancing.await() } }
            assertEquals(cancelled.message, failure.message)
            assertTrue(advancing.isCancelled)
            assertEquals(1, channel.closes)
        }
    }

    @Test
    fun `a stopped host classifies even an already closed channel as worker loss`() = runBlocking<Unit> {
        SharedWorkerChannel(IdleProcess(), this).use { host ->
            val channel = host.cell(1)
            channel.close()
            host.close()
            assertThrows(CodeModeWorkerLostException::class.java) {
                runBlocking { channel.exchange(CodeModeWire.completedFrame("", null)) }
            }
        }
    }

    @Test
    fun `an explicitly closed channel on a live host stays a local closure`() = runBlocking<Unit> {
        SharedWorkerChannel(IdleProcess(), this).use { host ->
            val channel = host.cell(1)
            channel.close()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { channel.exchange(CodeModeWire.completedFrame("", null)) }
            }
        }
    }

    private fun resultCell(channel: CellChannel) = JvmCodeModeCell(
        channel,
        WorkerReply(listOf(CodeModeCall("1", "Read", JsonObject(emptyMap()))), null, null),
        setOf("Read"),
        ReleaseCodeModeCell {},
    )

    private suspend fun assertStoppedExchange(
        cell: JvmCodeModeCell,
        channel: PausedCellChannel,
        results: List<CodeModeResult>,
    ) = supervisorScope {
        val advancing = async { cell.advance(results) }
        channel.entered.await()
        cell.stop()
        channel.proceed.complete(Unit)
        assertThrows(CodeModeWorkerLostException::class.java) { runBlocking { advancing.await() } }
        assertEquals(1, channel.closes)
        cell.close()
        cell.stop()
        assertEquals(1, channel.closes)
    }

    private class PausedCellChannel : CellChannel {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var closes = 0
        var exchangeFailure: Exception? = null
        private var onExit: WorkerExited? = null

        override suspend fun exchange(frame: JsonObject): JsonObject {
            entered.complete(Unit)
            proceed.await()
            exchangeFailure?.let { throw it }
            check(closes == 0) { "Code-mode cell is closed" }
            return CodeModeWire.completedFrame("unused", null)
        }

        override fun afterExit(action: WorkerExited) {
            onExit = action
        }

        override fun close() {
            closes++
            onExit?.invoke()
        }
    }

    private class IdleProcess : Process() {
        private val exited = CompletableFuture<Process>()

        // An idle worker's stdout blocks until it exits. An empty stream would hit EOF at once, and the
        // host's reader would close the host before a test touches it.
        private val stdout = PipedInputStream()
        private val stdoutWriter = PipedOutputStream(stdout)

        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun onExit(): CompletableFuture<Process> = exited
        override fun waitFor(): Int {
            exited.get()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.isDone
        override fun exitValue(): Int = if (exited.isDone) 0 else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = !exited.isDone
        override fun destroy() {
            stdoutWriter.close()
            exited.complete(this)
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}
