package splice.codemode.v4388

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.codemode.JvmCodeModeRuntime
import splice.codemode.SCRIPT_DEADLINE_MS
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

/** V4-388: the cell exposes codex's globals — tools.<Name>(args) under codex's identifiers, text(),
 *  exit(), ALL_TOOLS — so the manual the model reads is the API the cell runs. */
@Timeout(60)
class CodeModeCodexGlobalsTest {
    private val testClasspath: String = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `tools dot Name calls the client tool by its client name and text returns the evidence`() = runBlocking {
        runtime().use { runtime ->
            val source = """
                // @exec: {"yield_time_ms": 10000}
                const [a, b] = await Promise.allSettled([
                  tools.Read({file_path: "/a"}),
                  tools.mcp__ast_grep__find_code({pattern: "x"}),
                ]);
                text(a.value);
                text({found: b.value});
                exit();
                text("never");
            """.trimIndent()
            val cell = runtime.start(source, setOf("Read", "mcp__ast-grep__find_code"))
            val calls = (cell.advance() as CodeModeStep.Calls).calls
            assertEquals(listOf("Read", "mcp__ast-grep__find_code"), calls.map { it.name })
            assertEquals(Json.parseToJsonElement("""{"file_path":"/a"}"""), calls[0].arguments)
            val completed = cell.advance(
                listOf(CodeModeResult(calls[0].id, "alpha"), CodeModeResult(calls[1].id, "beta")),
            ) as CodeModeStep.Completed
            assertNull(completed.error)
            assertEquals("alpha\n{\"found\":\"beta\"}", completed.output)
        }
    }

    @Test
    fun `ALL_TOOLS lists every nested tool with its description and tools dot call still runs`() = runBlocking {
        runtime().use { runtime ->
            val source = """
                text(ALL_TOOLS.map(t => t.name + "=" + t.description).join(","));
                await tools.call("Read", {});
            """.trimIndent()
            val descriptions = mapOf("Read" to "Reads a file.", "mcp__ast-grep__find_code" to "Finds code.")
            val cell = runtime.start(source, descriptions.keys, descriptions)
            val call = (cell.advance() as CodeModeStep.Calls).calls.single()
            assertEquals("Read", call.name)
            val completed = cell.advance(listOf(CodeModeResult(call.id, ""))) as CodeModeStep.Completed
            assertNull(completed.error)
            assertTrue(
                completed.output.startsWith("Read=Reads a file.,mcp__ast_grep__find_code=Finds code."),
                completed.output,
            )
        }
    }

    @Test
    fun `text throws what JSON dot stringify throws, as codex's text does`() = runBlocking {
        runtime().use { runtime ->
            val source = """
                const cycle = {};
                cycle.self = cycle;
                try { text(cycle); } catch (error) { text("threw " + error.name); }
                text(null);
                text(7n);
            """.trimIndent()
            val completed = runtime.start(source, setOf("Read")).advance() as CodeModeStep.Completed
            assertNull(completed.error)
            assertEquals("threw TypeError\nnull\n7", completed.output)
        }
    }

    @Test
    fun `descriptions fill the frame at their escaped size and the rest reach ALL_TOOLS as a marker`() = runBlocking {
        runtime().use { runtime ->
            // \u0001 escapes to six bytes: five of these raw-fit any 512 KiB budget but encode to 1.8 MB.
            val descriptions = (0..4).associate { "t$it" to "\u0001".repeat(60_000) }
            val source = "const omitted = t => t.description.startsWith(\"(description omitted\");\n" +
                "text(ALL_TOOLS.map(t => t.name + \":\" + (omitted(t) ? \"omitted\" : t.description.length)).join(\",\"));"
            val completed = runtime.start(source, descriptions.keys, descriptions).advance() as CodeModeStep.Completed
            assertNull(completed.error)
            assertEquals("t0:60000,t1:60000,t2:omitted,t3:omitted,t4:omitted", completed.output)
        }
    }

    @Test
    fun `a nested tool called with no argument sends an empty object, as codex does`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("await tools.TaskList();", setOf("TaskList"))
            val call = (cell.advance() as CodeModeStep.Calls).calls.single()
            assertEquals(Json.parseToJsonElement("{}"), call.arguments)
        }
    }

    private fun runtime(): JvmCodeModeRuntime =
        JvmCodeModeRuntime(advanceTimeoutMs = SCRIPT_DEADLINE_MS, workerClasspath = testClasspath)
}
