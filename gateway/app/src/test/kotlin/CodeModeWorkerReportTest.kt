import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.app.codemode.JvmCodeModeRuntime
import splice.spi.CodeModeLimits
import splice.spi.CodeModeStep

/** What a cell reports back: a failure names its reason, and a verbose log is cut, never fatal. */
class CodeModeWorkerReportTest {
    private val testClasspath: String = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `unallowed tool completes with the rejection reason`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("await tools.call(\"Write\", {});", setOf("Read"))
            val error = checkNotNull(completed(cell.advance()).error)
            assertTrue(error.startsWith("Code execution failed: Error: Tool is not allowed"), error)
        }
    }

    @Test
    fun `a rejected client result carries the tool error text and the log so far`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("console.log(\"before\"); await tools.call(\"Read\", {});", setOf("Read"))
            val calls = (cell.advance() as CodeModeStep.Calls).calls
            val result = splice.spi.CodeModeResult(calls.single().id, "ENOENT: no such file", true)
            val completed = completed(cell.advance(listOf(result)))
            assertEquals("", completed.output)
            val error = checkNotNull(completed.error)
            assertTrue(error.startsWith("Code execution failed: Error: ENOENT: no such file"), error)
            assertTrue(error.endsWith("Output before the failure:\nbefore"), error)
        }
    }

    @Test
    fun `syntax failure is returned without source text`() = runBlocking {
        runtime().use { runtime ->
            val cell = runtime.start("const = ;", emptySet())
            val completed = completed(cell.advance())
            assertEquals("", completed.output)
            val error = checkNotNull(completed.error)
            assertTrue(error.startsWith("Code execution failed: SyntaxError"), error)
            assertFalse(error.contains("const = ;"), error)
        }
    }

    @Test
    @Timeout(3)
    fun `output past the text ceiling is truncated behind a marker not fatal`() = runBlocking {
        runtime().use { runtime ->
            val source = "console.log(\"x\".repeat(65537)); console.log(\"tail\"); return \"done\";"
            val completed = completed(runtime.start(source, emptySet()).advance())
            assertNull(completed.error)
            assertTrue(CodeModeLimits.fitsText(completed.output))
            assertTrue(completed.output.endsWith("[truncated 69 chars]\ndone"), completed.output.takeLast(80))
            assertTrue(completed.output.startsWith("x".repeat(1000)))
        }
    }

    private fun runtime(): JvmCodeModeRuntime = JvmCodeModeRuntime(workerClasspath = testClasspath)

    private fun completed(step: CodeModeStep): CodeModeStep.Completed {
        assertTrue(step is CodeModeStep.Completed)
        return step as CodeModeStep.Completed
    }
}
