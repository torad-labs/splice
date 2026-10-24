package splice.codemode

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.host.PoolLimits
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeOptions
import splice.upstream.failure.CodeModeCapacityException

class NativeCodeModeRuntimeTest {
    @Test
    fun `explicit yield retains one cell and wait returns only its suffix`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val cell = runtime.startNativeSession(
                "yield",
                "text('before'); yield_control(); text('after');",
                emptySet(),
                emptyMap(),
                NativeCodeModeOptions(),
            )
            val yielded = cell.advance() as CodeModeStep.Yielded
            assertEquals(cell.cellId, yielded.cellId)
            assertEquals("before", yielded.output)
            val completed = cell.observe(NATIVE_TEST_WAIT_MS) as CodeModeStep.Completed
            assertEquals("after", completed.output)
            assertEquals(null, completed.error)
        }
    }

    @Test
    fun `stored values survive cells and remain isolated between sessions`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            runtime.startSession("a", "store('value', {count: 7});", emptySet()).advance()
            val retained = runtime.startSession("a", "text(load('value'));", emptySet()).advance()
            assertEquals("{\"count\":7}", (retained as CodeModeStep.Completed).output)
            val isolated = runtime.startSession("b", "text(load('value'));", emptySet()).advance()
            assertEquals("undefined", (isolated as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `parallel callbacks retain independent promises and fast dependent branch advances`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val source = """
                const fast = tools.Read({key: 'fast'}).then(value => tools.Read({key: value}));
                const slow = tools.Read({key: 'slow'});
                text(await fast); text(await slow);
            """.trimIndent()
            val cell = runtime.startSession("callbacks", source, setOf("Read"))
            val issued = (cell.advance() as CodeModeStep.Calls).calls.toMutableList()
            if (issued.size == 1) issued.addAll((cell.advance() as CodeModeStep.Calls).calls)
            val fast = issued.single { it.arguments.toString().contains("fast") }
            val slow = issued.single { it.id != fast.id }
            val dependent = cell.advance(listOf(CodeModeResult(fast.id, "next"))) as CodeModeStep.Calls
            assertEquals("{\"key\":\"next\"}", dependent.calls.single().arguments.toString())
            val done = cell.advance(
                listOf(
                    CodeModeResult(dependent.calls.single().id, "fast-finished"),
                    CodeModeResult(slow.id, "slow-finished"),
                ),
            ) as CodeModeStep.Completed
            assertEquals("fast-finished\nslow-finished", done.output)
        }
    }

    @Test
    fun `notification arrives while the native cell remains available for completion`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val cell = runtime.startSession("notify", "notify('working'); text('done');", emptySet())
            assertEquals("working", (cell.advance() as CodeModeStep.Notified).text)
            assertEquals("done", (cell.advance() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `invalid module source causes no prefix effects`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val failed = runtime.startSession("parse", "store('bad', true); const = ;", emptySet()).advance()
            assertNotNull((failed as CodeModeStep.Completed).error)
            val observed = runtime.startSession("parse", "text(load('bad'));", emptySet()).advance()
            assertEquals("undefined", (observed as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `timed yield and termination address only their own running cell`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val cell = runtime.startNativeSession(
                "terminate",
                "text('started'); await new Promise(() => {});",
                emptySet(),
                emptyMap(),
                NativeCodeModeOptions(yieldTimeMs = 1),
            )
            assertEquals("started", (cell.advance() as CodeModeStep.Yielded).output)
            assertTrue((cell.terminate() as CodeModeStep.Completed).terminated)
            val sibling = runtime.startSession("terminate", "text('sibling');", emptySet()).advance()
            assertEquals("sibling", (sibling as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `media content stays typed through native completion`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val source = "audio('data:audio/wav;base64,AAAA'); image('data:image/png;base64,YQ==');"
            val completed = runtime.startSession("media", source, emptySet()).advance() as CodeModeStep.Completed
            assertEquals("", completed.output)
            assertEquals(
                listOf(
                    buildJsonObject {
                        put("type", "input_audio")
                        put("audio_url", "data:audio/wav;base64,AAAA")
                    },
                    buildJsonObject {
                        put("type", "input_image")
                        put("image_url", "data:image/png;base64,YQ==")
                        put("detail", "high")
                    },
                ),
                completed.content,
            )
        }
    }

    @Test
    fun `simultaneous sessions with the same native cell id keep their callbacks isolated`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val a = runtime.startSession("a", "text(await tools.Read({key:'a'}));", setOf("Read"))
            val b = runtime.startSession("b", "text(await tools.Read({key:'b'}));", setOf("Read"))
            val aCall = (a.advance() as CodeModeStep.Calls).calls.single()
            val bCall = (b.advance() as CodeModeStep.Calls).calls.single()
            assertEquals("{\"key\":\"a\"}", aCall.arguments.toString())
            assertEquals("{\"key\":\"b\"}", bCall.arguments.toString())
            val aDone = a.advance(listOf(CodeModeResult(aCall.id, "answer-a"))) as CodeModeStep.Completed
            val bDone = b.advance(listOf(CodeModeResult(bCall.id, "answer-b"))) as CodeModeStep.Completed
            assertEquals("answer-a", aDone.output)
            assertEquals("answer-b", bDone.output)
        }
    }

    @Test
    fun `callback results can arrive while a separate observation is pending`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val cell = runtime.startNativeSession(
                "independent",
                "text(await tools.Read({key:'a'}));",
                setOf("Read"),
                emptyMap(),
                NativeCodeModeOptions(),
            )
            val call = (cell.advance() as CodeModeStep.Calls).calls.single()
            val observation = async { cell.observe(NATIVE_TEST_WAIT_MS) }
            yield()
            cell.submitResults(listOf(CodeModeResult(call.id, "accepted")))
            assertEquals("accepted", (observation.await() as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `malformed client function input rejects its promise without issuing a callback`() = runTest {
        NativeCodeModeRuntime().use { runtime ->
            val source = "try { await tools.Read('wrong'); } catch(e) { text(String(e)); }"
            val result = runtime.startSession("invalid-input", source, setOf("Read")).advance()
            assertEquals(
                "Client function tool Read requires a JSON object argument",
                (result as CodeModeStep.Completed).output,
            )
        }
    }

    @Test
    fun `yielded cells retain configured capacity until their termination is acknowledged`() = runTest {
        NativeCodeModeRuntime(
            limits = PoolLimits(memoryBudgetMb = NATIVE_TEST_MEMORY_MB),
            cellHeapMb = NATIVE_TEST_HEAP_MB,
        ).use { runtime ->
            val cell = runtime.startNativeSession(
                "capacity",
                "yield_control(); await new Promise(()=>{});",
                emptySet(),
                emptyMap(),
                NativeCodeModeOptions(),
            )
            assertTrue(cell.advance() is CodeModeStep.Yielded)
            var refused = false
            try {
                runtime.startSession("capacity", "text('must not start');", emptySet())
            } catch (_: CodeModeCapacityException) {
                refused = true
            }
            assertTrue(refused)
            cell.terminate()
            val resumed = runtime.startSession("capacity", "text('after release');", emptySet()).advance()
            assertEquals("after release", (resumed as CodeModeStep.Completed).output)
        }
    }
}

private const val NATIVE_TEST_WAIT_MS: Long = 1_000
private const val NATIVE_TEST_MEMORY_MB: Long = 224
private const val NATIVE_TEST_HEAP_MB: Int = 16
