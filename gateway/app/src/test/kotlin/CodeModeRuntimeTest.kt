import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.app.codemode.CodeModeFrames
import splice.app.codemode.CodeModeWire
import splice.app.codemode.JvmCodeModeCell
import splice.app.codemode.JvmCodeModeRuntime
import splice.app.codemode.ReleaseCodeModeCell
import splice.app.codemode.WorkerChannel
import splice.app.codemode.WorkerPermit
import splice.app.codemode.WorkerReply
import splice.spi.CodeModeCall
import splice.spi.CodeModeInfrastructureCategory
import splice.spi.CodeModeInfrastructureClass
import splice.spi.CodeModeInfrastructureException
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep
import splice.spi.CodeModeTimeoutException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
    fun `bundled regex language is available to JavaScript`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("""return "codes 17 and 4".match(/\d+/g).join("+");""", emptySet())
            val result = completed(cell.advance())
            assertEquals("17+4", result.output)
            assertEquals(null, result.error)
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
    fun `unallowed tool completes with an execution error`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("await tools.call(\"Write\", {});", setOf("Read"))
            assertNotNull(completed(cell.advance()).error)
        }
    }

    @Test
    @Timeout(3)
    fun `worker protocol fault is fatal rather than a completed guest error`() {
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp",
            testClasspath,
            "splice.app.codemode.CodeModeWorker",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            DataOutputStream(process.outputStream.buffered()).use { input ->
                CodeModeWire.write(input, buildJsonObject { put("type", "start") })
            }
            val reply = DataInputStream(process.inputStream.buffered()).use(CodeModeWire::read)
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
    fun `syntax failure is returned without source text`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("const = ;", emptySet())
            val completed = completed(cell.advance())
            assertEquals("", completed.output)
            assertEquals("Code execution failed", completed.error)
        }
    }

    @Test
    fun `active worker cap rejects a second live cell`() = runBlocking {
        JvmCodeModeRuntime(maxWorkers = 1, workerClasspath = testClasspath).use { runtime ->
            val first = runtime.start("await tools.call(\"Read\", {});", setOf("Read"))
            assertThrows(IOException::class.java) {
                runBlocking { runtime.start("return \"second\";", emptySet()) }
            }
            first.close()
            val replacement = runtime.start("return \"replacement\";", emptySet())
            assertEquals("replacement", completed(replacement.advance()).output)
        }
    }

    @Test
    fun `output cap stops the worker`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("console.log(\"x\".repeat(65537));", emptySet())
            assertNotNull(completed(cell.advance()).error)
        }
    }

    @Test
    @Timeout(10)
    fun `runaway source times out and releases its worker slot`() = runBlocking {
        JvmCodeModeRuntime(
            maxWorkers = 1,
            advanceTimeoutMs = 1_000,
            workerClasspath = testClasspath,
        ).use { runtime ->
            val reclamation = CodeModeWorkerReclamation(this)
            val startedAt = System.nanoTime()
            val timeout = assertThrows(IOException::class.java) {
                runBlocking { runtime.start("while (true) {}", emptySet()) }
            }
            assertEquals("Code-mode worker timed out", timeout.message)
            assertTrue(timeout is CodeModeTimeoutException)
            assertEquals(1_000L, (timeout as CodeModeTimeoutException).timeoutMillis)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_000)
            assertTrue(timeout.cause !is CancellationException)

            reclamation.assertReclaimed(runtime)
        }
    }

    @Test
    @Timeout(10)
    fun `cancelling startup reaps the worker and releases capacity`() = runBlocking {
        JvmCodeModeRuntime(
            maxWorkers = 1,
            advanceTimeoutMs = 5_000,
            workerClasspath = testClasspath,
        ).use { runtime ->
            val before = ProcessHandle.current().children().use { children -> children.map { it.pid() }.toList() }
            val startup = async { runtime.start("while (true) {}", emptySet()) }
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
            startup.cancelAndJoin()
            child.onExit().get(1, TimeUnit.SECONDS)
            assertTrue(!child.isAlive, "cancelled startup must reap the observed child")

            val replacement = runtime.start("return \"reaped\";", emptySet())
            assertEquals("reaped", completed(replacement.advance()).output)
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
            assertTrue(!child.isAlive)
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

    @Test
    fun `closed runtime rejects new cells`() = runBlocking {
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

    private fun assertClosed(cell: splice.spi.CodeModeCell) {
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
}
