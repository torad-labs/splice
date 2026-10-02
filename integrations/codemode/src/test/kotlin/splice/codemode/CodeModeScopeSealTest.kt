package splice.codemode

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.codemode.CodeModeProtocol
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep

class CodeModeScopeSealTest {
    private val classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `sealed primitive conversion dispatches before the source ends`(): Unit = runBlocking {
        JvmCodeModeRuntime(workerClasspath = classpath).use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            val source = sealed(input)
            input.send(CodeModeSourcePart.Delta("let n=1; text(await tools.Read({path:String(n)}));\ntext("))
            val first = async { runtime.startStreaming(source, setOf("Read")).let { it to it.advance() } }
            val (cell, step) = withTimeout(2_000) { first.await() }
            assertEquals("1", (step as CodeModeStep.Calls).calls.single().arguments["path"]?.toString()?.trim('"'))
            input.send(CodeModeSourcePart.Complete("'done');"))
            val done = cell.advance(listOf(CodeModeResult("1", "ran"))) as CodeModeStep.Completed
            assertEquals("ran\ndone", done.output)
            assertEquals(null, done.error)
            input.close()
        }
    }

    @Test
    fun `late sealed declarations fail without rerunning an earlier permitted call`(): Unit = runBlocking {
        JvmCodeModeRuntime(workerClasspath = classpath).use { runtime ->
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            input.send(CodeModeSourcePart.Delta("text(await tools.Read({path:String(1)}));\nlet "))
            val cell = runtime.startStreaming(sealed(input), setOf("Read"))
            assertTrue(cell.advance() is CodeModeStep.Calls)
            input.send(CodeModeSourcePart.Complete("String; text(await tools.Read({path:'never'}));"))
            val done = cell.advance(listOf(CodeModeResult("1", "already ran"))) as CodeModeStep.Completed
            assertEquals("already ran", done.output)
            assertEquals("SyntaxError: Streaming source cannot declare sealed binding 'String'", done.error)
            input.close()
        }
    }

    @Test
    fun `object conversions remain deferred despite their namespace seal`(): Unit = runBlocking {
        JvmCodeModeRuntime(workerClasspath = classpath).use { runtime ->
            runtime.start("return 'warm';", emptySet()).use { it.advance() }
            val input = Channel<CodeModeSourcePart>(Channel.UNLIMITED)
            val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
            val reads = java.util.concurrent.atomic.AtomicInteger()
            val source = object : CodeModeSealedSource {
                override val sealedGlobals: Set<String> = setOf("String")
                override suspend fun read(): CodeModeSourcePart {
                    if (reads.incrementAndGet() == 2) waiting.complete(Unit)
                    return input.receive()
                }
            }
            input.send(
                CodeModeSourcePart.Delta(
                    "const obj={toString(){return 'object';}};\n" +
                        "text(await tools.Read({path:String(obj)}));\ntext(",
                ),
            )
            val first = async { runtime.startStreaming(source, setOf("Read")).let { it to it.advance() } }
            withTimeout(2_000) { waiting.await() }
            assertTrue(!first.isCompleted)
            input.send(CodeModeSourcePart.Complete("'done');"))
            val (cell, step) = withTimeout(2_000) { first.await() }
            assertEquals("object", (step as CodeModeStep.Calls).calls.single().arguments["path"]?.toString()?.trim('"'))
            val done = cell.advance(listOf(CodeModeResult("1", "ran"))) as CodeModeStep.Completed
            assertEquals("ran\ndone", done.output)
            assertEquals(null, done.error)
            input.close()
        }
    }

    @Test
    fun `seal metadata cannot overflow a catalog fitted to the exact frame ceiling`() {
        val source = " ".repeat(CodeModeWire.maxTextBytes)
        val count = CodeModeWire.maxFrameBytes / CodeModeWire.maxTextBytes
        val tools = (0 until count).map { "Read$it" }.toSet()
        val bare = CodeModeProtocol.encodeFrame(CodeModeWire.startFrame(source, tools)).size
        val room = CodeModeWire.maxFrameBytes - bare
        val descriptions = tools.sorted().withIndex().associate { (index, name) ->
            name to "d".repeat(room / count + if (index < room % count) 1 else 0)
        }
        val ordinary = CodeModeProtocol.encodeFrame(CodeModeWire.startFrame(source, tools, descriptions))
        assertEquals(CodeModeWire.maxFrameBytes, ordinary.size)
        val stream = StreamingCodeModeWire.startFrame(source, tools, descriptions, setOf("String"))
        val encoded = CodeModeProtocol.encodeFrame(stream)
        assertTrue(encoded.size <= CodeModeWire.maxFrameBytes)
        val unreserved = StreamingCodeModeWire.withSeal(
            CodeModeWire.startFrame(source, tools, descriptions),
            setOf("String"),
        )
        assertTrue(CodeModeProtocol.encodeFrame(unreserved).size > CodeModeWire.maxFrameBytes)
    }

    private fun sealed(input: Channel<CodeModeSourcePart>): CodeModeSealedSource =
        object : CodeModeSealedSource {
            override val sealedGlobals: Set<String> = setOf("String")
            override suspend fun read(): CodeModeSourcePart = input.receive()
        }
}
