package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.host.HostLaunch
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep
import java.util.concurrent.atomic.AtomicInteger

// why: the statement lexer stops at every slash; 40 stops doubled a 256-token buffer past the worker's 512 MB heap.
private const val SLASH_RUN = 40

class CodeModeStatementBoundaryTest {
    private val classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `if else remains one statement until its continuation is closed`(): Unit = runBlocking {
        heldStatement(
            "if (true) { text(await tools.Read({path:'yes'})); }\n",
            "else { text(await tools.Read({path:'no'})); }\ntext(",
            listOf("yes"),
        )
    }

    @Test
    fun `try catch finally is not split before the exception handler`(): Unit = runBlocking {
        heldStatement(
            "try { text(await tools.Read({path:'try'})); }\n",
            "catch (error) { text('caught'); } finally { text('finally'); }\ntext(",
            listOf("try"),
            failTool = true,
        )
    }

    @Test
    fun `a multi line for body is never executed while its block is unfinished`(): Unit = runBlocking {
        heldStatement(
            "for (let i=0; i<2; i++) {\ntext(await tools.Read({path:String(i)}));\n",
            "}\ntext(",
            listOf("0", "1"),
        )
    }

    @Test
    fun `a multi line call expression waits for its closing argument list`(): Unit = runBlocking {
        heldStatement("text(await tools.Read({\npath:'line'\n", "}));\ntext(", listOf("line"))
    }

    @Test
    fun `a multi line template cut after its substitution streams to its end`(): Unit = runBlocking {
        val path = (1..SLASH_RUN).joinToString("/") { "d$it" }
        streamsToEnd(
            "const dir = 'src';\ntext(`listing:\n\${dir}/$path\n",
            "done`);",
            "listing:\nsrc/$path\ndone",
        )
    }

    @Test
    fun `a statement with a long run of divisions runs while its source still streams`(): Unit = runBlocking {
        val quotient = (2..SLASH_RUN).joinToString("/")
        streamsToEnd("const x = 1/$quotient;\n", "text(x > 0 ? 'divided' : 'zero');", "divided")
    }

    @Test
    fun `a late syntax error keeps both completed calls and their output`(): Unit = runBlocking {
        runtime().use { runtime ->
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            input.send(
                CodeModeSourcePart.Delta(
                    "text(await tools.Read({path:'one'}));\ntext(await tools.Read({path:'two'}));\nconst ",
                ),
            )
            val cell = runtime.startStreaming(CodeModeSource { input.receive() }, setOf("Read"))
            assertEquals("one", path(calls(cell.advance()).single()))
            assertEquals("two", path(calls(cell.advance(listOf(CodeModeResult("1", "first ran")))).single()))
            input.send(CodeModeSourcePart.Complete("= ;"))
            val completed = cell.advance(listOf(CodeModeResult("2", "second ran"))) as CodeModeStep.Completed
            assertEquals("first ran\nsecond ran", completed.output)
            assertTrue(completed.error.orEmpty().startsWith("SyntaxError:"), completed.toString())
            input.close()
        }
    }

    @Test
    fun `a torn source reports already executed output without dispatching the incomplete tail`(): Unit = runBlocking {
        runtime().use { runtime ->
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            input.send(CodeModeSourcePart.Delta("text(await tools.Read({path:'one'}));\ntext("))
            val cell = runtime.startStreaming(CodeModeSource { input.receive() }, setOf("Read"))
            assertEquals("one", path(calls(cell.advance()).single()))
            input.send(CodeModeSourcePart.Failed("Upstream response interrupted; source was not rerun"))
            val completed = cell.advance(listOf(CodeModeResult("1", "already ran"))) as CodeModeStep.Completed
            assertEquals("already ran", completed.output)
            assertEquals("Upstream response interrupted; source was not rerun", completed.error)
            input.close()
        }
    }

    @Test
    fun `a return before source completion cannot hide a later syntax error`(): Unit = runBlocking {
        runtime().use { runtime ->
            val parts = java.util.ArrayDeque<CodeModeSourcePart>()
            parts.add(CodeModeSourcePart.Delta("text(await tools.Read({path:'one'}));\nreturn 'returned';\nconst "))
            parts.add(CodeModeSourcePart.Complete("= ;"))
            val cell = runtime.startStreaming(CodeModeSource { parts.removeFirst() }, setOf("Read"))
            assertEquals("one", path(calls(cell.advance()).single()))
            val completed = cell.advance(listOf(CodeModeResult("1", "already ran"))) as CodeModeStep.Completed
            assertEquals("already ran\nreturned", completed.output)
            assertTrue(completed.error.orEmpty().startsWith("SyntaxError:"), completed.toString())
        }
    }

    @Test
    fun `a return before a source tear remains an honest interrupted completion`(): Unit = runBlocking {
        runtime().use { runtime ->
            val parts = java.util.ArrayDeque<CodeModeSourcePart>()
            parts.add(CodeModeSourcePart.Delta("text(await tools.Read({path:'one'}));\nreturn 'returned';\ntext("))
            parts.add(CodeModeSourcePart.Failed("Upstream source interrupted"))
            val cell = runtime.startStreaming(CodeModeSource { parts.removeFirst() }, setOf("Read"))
            assertEquals("one", path(calls(cell.advance()).single()))
            val completed = cell.advance(listOf(CodeModeResult("1", "already ran"))) as CodeModeStep.Completed
            assertEquals("already ran\nreturned", completed.output)
            assertEquals("Upstream source interrupted", completed.error)
        }
    }

    @Test
    fun `an unreachable unknown reference does not delay the next tool call`(): Unit = runBlocking {
        runtime().use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            input.send(
                CodeModeSourcePart.Delta(
                    "if(false){text(missing);}\ntext(await tools.Read({path:'now'}));\ntext(",
                ),
            )
            val pending = async {
                runtime.startStreaming(CodeModeSource { input.receive() }, setOf("Read"))
                    .let { cell -> cell to cell.advance() }
            }
            val (cell, first) = withTimeout(30_000) { pending.await() }
            assertEquals("now", path(calls(first).single()))
            input.send(CodeModeSourcePart.Complete("'done');"))
            val completed = cell.advance(listOf(CodeModeResult("1", "now ran"))) as CodeModeStep.Completed
            assertEquals("now ran\ndone", completed.output)
            input.close()
        }
    }

    @Test
    fun `unreachable short circuit references do not postpone an already closed tool call`(): Unit = runBlocking {
        earlyCall("false && text(missing);\ntext(await tools.Read({path:'now'}));\ntext(", "'done');")
        earlyCall("true || text(missing);\ntext(await tools.Read({path:'now'}));\ntext(", "'done');")
    }

    @Test
    fun `a following incomplete while does not hold a preceding complete tool call`(): Unit = runBlocking {
        earlyCall("text(await tools.Read({path:'now'}));\nwhile (", "false){}")
    }

    private suspend fun earlyCall(first: String, last: String) = kotlinx.coroutines.coroutineScope {
        runtime().use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            input.send(CodeModeSourcePart.Delta(first))
            val pending = async {
                runtime.startStreaming(CodeModeSource { input.receive() }, setOf("Read"))
                    .let { cell -> cell to cell.advance() }
            }
            val (cell, step) = withTimeout(30_000) { pending.await() }
            assertEquals("now", path(calls(step).single()))
            input.send(CodeModeSourcePart.Complete(last))
            val completed = cell.advance(listOf(CodeModeResult("1", "ran"))) as CodeModeStep.Completed
            assertEquals(null, completed.error, completed.toString())
            input.close()
        }
    }

    @Test
    fun `a source read IOException preserves already executed output`(): Unit = runBlocking {
        runtime().use { runtime ->
            val reads = AtomicInteger()
            val source = CodeModeSource {
                if (reads.incrementAndGet() == 1) {
                    CodeModeSourcePart.Delta("text('ran');\ntext(")
                } else {
                    throw java.io.IOException("synthetic upstream tear")
                }
            }
            val completed = runtime.startStreaming(source, emptySet()).advance() as CodeModeStep.Completed
            assertEquals("ran", completed.output)
            assertTrue(completed.error.orEmpty().contains("interrupted"), completed.toString())
        }
    }

    /** Sends [first] as a frame of its own, so the worker reads it while the source is still open, then [last]. */
    private suspend fun streamsToEnd(first: String, last: String, expected: String) {
        runtime().use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val parts = ArrayDeque<CodeModeSourcePart>(
                listOf(CodeModeSourcePart.Delta(first), CodeModeSourcePart.Complete(last)),
            )
            val step = withTimeout(30_000) {
                runtime.startStreaming(CodeModeSource { parts.removeFirst() }, emptySet()).advance()
            }
            val completed = step as CodeModeStep.Completed
            assertEquals(null, completed.error, completed.toString())
            assertEquals(expected, completed.output)
        }
    }

    private suspend fun heldStatement(
        first: String,
        rest: String,
        expected: List<String>,
        failTool: Boolean = false,
    ) = kotlinx.coroutines.coroutineScope {
        runtime().use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            val reads = AtomicInteger()
            val held = CompletableDeferred<Unit>()
            val source = object : CodeModeSealedSource {
                override val sealedGlobals: Set<String> = setOf("String")
                override suspend fun read(): CodeModeSourcePart {
                    if (reads.incrementAndGet() == 2) held.complete(Unit)
                    return input.receive()
                }
            }
            input.send(CodeModeSourcePart.Delta(first))
            val pending = async {
                runtime.startStreaming(source, setOf("Read")).let { cell -> cell to cell.advance() }
            }
            withTimeout(30_000) { held.await() }
            assertFalse(pending.isCompleted, "The incomplete compound statement must not publish a call")
            input.send(CodeModeSourcePart.Delta(rest))
            val (cell, initial) = withTimeout(30_000) { pending.await() }
            input.send(CodeModeSourcePart.Complete("'after');"))
            val observed = mutableListOf<String>()
            var step = initial
            while (step is CodeModeStep.Calls) {
                val results = step.calls.map { call ->
                    observed += path(call)
                    CodeModeResult(call.id, "result:${path(call)}", failTool)
                }
                step = cell.advance(results)
            }
            assertEquals(expected, observed)
            assertEquals(null, (step as CodeModeStep.Completed).error, step.toString())
            assertTrue(step.output.endsWith("after"), step.toString())
            input.close()
        }
    }

    private fun runtime(): JvmCodeModeRuntime = JvmCodeModeRuntime(launch = HostLaunch(classpath = classpath))

    private fun calls(step: CodeModeStep): List<CodeModeCall> {
        assertTrue(step is CodeModeStep.Calls, step.toString())
        return (step as CodeModeStep.Calls).calls
    }

    private fun path(call: CodeModeCall): String = call.arguments["path"]?.toString()?.trim('"').orEmpty()
}
