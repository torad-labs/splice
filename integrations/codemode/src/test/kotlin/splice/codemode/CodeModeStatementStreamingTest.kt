package splice.codemode

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeSourcePersistenceException
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureCategory
import splice.upstream.failure.CodeModeInfrastructureClass
import splice.upstream.failure.CodeModeInfrastructureException
import java.io.IOException

class CodeModeStatementStreamingTest {
    private val classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    private class DurableSourceFailure(val diskFull: Boolean) :
        CodeModeSourcePersistenceException(IOException("synthetic durable source write refused"))

    @Test
    fun `a failed durable source read escapes unchanged and closes only its cell`(): Unit = runBlocking {
        JvmCodeModeRuntime(workerClasspath = classpath).use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val fault = DurableSourceFailure(diskFull = true)
            var reads = 0
            val source = CodeModeSource {
                if (reads++ == 0) CodeModeSourcePart.Delta("await tools.Read({});\n") else throw fault
            }
            runtime.startStreaming(source, setOf("Read")).use { cell ->
                val actual = assertThrows<DurableSourceFailure> { runBlocking { cell.advance() } }
                assertSame(fault, actual, "the caller must retain the original persistence failure category")
                assertTrue(actual.diskFull, "the source failure must retain its disk classification")
                assertEquals(2, reads, "uncommitted source must never be reread automatically")
            }
            val healthy = runtime.start("return 'healthy';", emptySet()).use { it.advance() } as CodeModeStep.Completed
            assertEquals("healthy", healthy.output)
        }
    }

    @Test
    fun `an infrastructure read failure stays a worker fault rather than an upstream source terminal`(): Unit =
        runBlocking {
            JvmCodeModeRuntime(workerClasspath = classpath).use { runtime ->
                runtime.start("return 'warm';", emptySet()).use { it.advance() }
                val fault = CodeModeInfrastructureException(
                    CodeModeInfrastructureCategory.HOST,
                    CodeModeInfrastructureClass.IO,
                )
                var reads = 0
                val source = CodeModeSource {
                    if (reads++ == 0) CodeModeSourcePart.Delta("await tools.Read({});\n") else throw fault
                }
                runtime.startStreaming(source, setOf("Read")).use { cell ->
                    val actual = assertThrows<CodeModeInfrastructureException> { runBlocking { cell.advance() } }
                    assertSame(fault, actual)
                    assertEquals(CodeModeInfrastructureCategory.HOST, actual.category)
                    assertEquals(CodeModeInfrastructureClass.IO, actual.faultClass)
                }
            }
        }

    @Test
    fun `a complete statement calls its tool before source completion`(): Unit = runBlocking {
        JvmCodeModeRuntime(workerClasspath = classpath).use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            val first = async {
                runtime.startStreaming(CodeModeSource { input.receive() }, setOf("Read")).also { cell ->
                    assertEquals("a", calls(cell.advance()).single().arguments["path"]?.toString()?.trim('"'))
                }
            }
            input.send(CodeModeSourcePart.Delta("text(await tools.Read({path: 'a'}));\ntext("))
            val cell = withTimeout(2_000) { first.await() }
            input.send(CodeModeSourcePart.Complete("'done');"))
            val completed = cell.advance(listOf(CodeModeResult("1", "A"))) as CodeModeStep.Completed
            assertEquals("A\ndone", completed.output)
            assertEquals(null, completed.error)
            cell.close()
            input.close()
        }
    }

    @Test
    fun `a captured closure sees a let mutated in a later statement`(): Unit = runBlocking {
        parity(
            listOf("let x = 1; const read = () => x;\n", "await tools.Read({path:'gate'});\n", "x = 2; text(read());"),
        )
    }

    @Test
    fun `a captured const keeps the native assignment error`(): Unit = runBlocking {
        parity(listOf("const x = 1;\n", "x = 2;\n", "text('unreached');"))
    }

    @Test
    fun `an earlier callback can resolve a later function declaration`(): Unit = runBlocking {
        parity(
            listOf(
                "const callback = () => later(); await tools.Read({path:'gate'});\n",
                "function later() { return 'later'; }\n",
                "text(callback());",
            ),
        )
    }

    @Test
    fun `a read before a later let preserves the native temporal dead zone error`(): Unit = runBlocking {
        parity(listOf("text(x);\n", "let x = 1;"))
    }

    @Test
    fun `text output and tool order match whole script execution`(): Unit = runBlocking {
        parity(
            listOf(
                "text('before'); text(await tools.Read({path:'one'}));\n",
                "text(await tools.Read({path:'two'}));\n",
                "text('after');",
            ),
        )
    }

    @Test
    fun `var redeclaration preserves the value seen by an earlier closure`(): Unit = runBlocking {
        parity(listOf("var x=1; const read=()=>x;\n", "var x;\n", "text(read());"))
    }

    @Test
    fun `destructuring declarations and local function parameters keep their scopes`(): Unit = runBlocking {
        parity(
            listOf(
                "const {value}={value:'A'}; const f=(value)=>value;\n",
                "text(await tools.Read({path:'gate'}));\n",
                "text(f('local')); text(value);",
            ),
        )
    }

    @Test
    fun `an unbound function call keeps its strict receiver`(): Unit = runBlocking {
        parity(listOf("function f(){ return this === undefined; }\n", "text(f());"))
    }

    @Test
    fun `multiple var bindings form one valid statement`(): Unit = runBlocking {
        parity(listOf("var a=1,b=2;\n", "text(a+b);"))
    }

    @Test
    fun `an earlier closure sees a later var as hoisted undefined before initialization`(): Unit = runBlocking {
        parity(listOf("const read=()=>laterVar; text(read());\n", "var laterVar=3;"))
    }

    @Test
    fun `direct eval sees a lexical binding from an earlier statement`(): Unit = runBlocking {
        parity(listOf("const x=7;\n", "text(eval('x'));"))
    }

    @Test
    fun `direct eval mutates a prior let and preserves local parameter shadowing`(): Unit = runBlocking {
        parity(
            listOf(
                "let x=7; const f=(x)=>eval('x');\n",
                "eval('x=8'); text(x); text(f('local'));",
            ),
        )
    }

    @Test
    fun `direct eval keeps const assignment and malformed source errors native`(): Unit = runBlocking {
        parity(listOf("const x=7;\n", "eval('x=8');"))
        parity(listOf("text('before');\n", "eval('const = ;');"))
    }

    @Test
    fun `direct eval retains its own declarations and non string identity`(): Unit = runBlocking {
        parity(listOf("const x=7;\n", "text(eval('const x=9; x')); text(eval(10)); text(x);"))
    }

    @Test
    fun `closure dependencies follow another captured function`(): Unit = runBlocking {
        parity(listOf("const first=()=>later; const second=()=>first();\n", "text(second());\n", "var later=3;"))
    }

    @Test
    fun `a future lexical declaration shadows a native global from the start`(): Unit = runBlocking {
        parity(listOf("text(typeof JSON);\nlet ", "JSON=1;"))
    }

    @Test
    fun `direct eval observes future var hoisting`(): Unit = runBlocking {
        parity(listOf("text(eval('x'));\nvar ", "x=1;"))
    }

    @Test
    fun `the final function declaration is hoisted over an earlier call`(): Unit = runBlocking {
        parity(
            listOf("function f(){return 'old';}\ntext(f());\ntext(", "'gate');\nfunction f(){return 'new';}"),
        )
    }

    @Test
    fun `unawaited promise callbacks do not move before later synchronous output`(): Unit = runBlocking {
        parity(listOf("Promise.resolve().then(()=>text('microtask'));\ntext(", "'sync');"))
    }

    @Test
    fun `unawaited tool promise callbacks keep their native output ordering`(): Unit = runBlocking {
        parity(listOf("tools.Read({path:'gate'}).then(()=>text('microtask'));\ntext(", "'sync');"))
        parity(listOf("const f=async()=>{await 0; text('microtask');}; f();\ntext(", "'sync');"))
    }

    @Test
    fun `the original strict arguments object survives statement wrappers`(): Unit = runBlocking {
        parity(listOf("text(arguments.length);\ntext(", "'done');"))
    }

    @Test
    fun `assignments to the provided text parameter retain the original mutable scope`(): Unit = runBlocking {
        parity(listOf("text=(value)=>console.log(value+'!');\n", "text('after');"))
    }

    private suspend fun parity(parts: List<String>) {
        JvmCodeModeRuntime(workerClasspath = classpath).use { runtime ->
            val script = parts.joinToString("")
            val whole = execute(runtime.start(script, setOf("Read")))
            val updates = java.util.ArrayDeque<CodeModeSourcePart>()
            parts.forEach { updates.add(CodeModeSourcePart.Delta(it)) }
            updates.add(CodeModeSourcePart.Complete())
            val streamed = execute(runtime.startStreaming(CodeModeSource { updates.removeFirst() }, setOf("Read")))
            assertEquals(whole, streamed, script)
            val complete = execute(
                runtime.startStreaming(CodeModeSource { CodeModeSourcePart.Complete(script) }, setOf("Read")),
            )
            assertEquals(whole, complete, script)
        }
    }

    private suspend fun execute(cell: splice.upstream.codemode.CodeModeCell): Pair<List<String>, CodeModeStep.Completed> {
        cell.use {
            val order = mutableListOf<String>()
            var step = cell.advance()
            while (step is CodeModeStep.Calls) {
                val returned = step.calls.map { call ->
                    val path = call.arguments["path"]?.toString()?.trim('"').orEmpty()
                    order += "${call.name}:$path"
                    CodeModeResult(call.id, "result:$path")
                }
                step = cell.advance(returned)
            }
            return order to (step as CodeModeStep.Completed)
        }
    }

    private fun calls(step: CodeModeStep): List<CodeModeCall> {
        org.junit.jupiter.api.Assertions.assertTrue(step is CodeModeStep.Calls, step.toString())
        return (step as CodeModeStep.Calls).calls
    }
}
