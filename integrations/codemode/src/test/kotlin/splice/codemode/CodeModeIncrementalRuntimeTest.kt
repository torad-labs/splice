package splice.codemode

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.host.HostLaunch
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

class CodeModeIncrementalRuntimeTest {
    @Test
    fun `partial result retains its unresolved sibling across advances`() = runBlocking {
        runtime().use { host ->
            val cell = host.start("await Promise.all([tools.call('Read', {}), tools.call('Read', {})]);", setOf("Read"))
            val first = calls(cell.advance())
            assertEquals(listOf("1", "2"), first.map(CodeModeCall::id))
            assertEquals(CodeModeStep.WaitingForResults(null), cell.advance(listOf(CodeModeResult("1", "a"))))
            assertTrue(cell.advance(listOf(CodeModeResult("2", "b"))) is CodeModeStep.Completed)
        }
    }

    @Test
    fun `a race dispatches its follow-up while the sibling callback remains pending`() = runBlocking {
        runtime().use { host ->
            val cell = host.start(
                "const winner = await Promise.race([" +
                    "tools.call('Read', {file: 'fast'}), tools.call('Read', {file: 'slow'})]);\n" +
                    "const follow = await tools.call('Read', {file: winner});",
                setOf("Read"),
            )
            assertEquals(listOf("1", "2"), calls(cell.advance()).map(CodeModeCall::id))
            val follow = calls(cell.advance(listOf(CodeModeResult("1", "fast-value"))))
            assertEquals(listOf("3"), follow.map(CodeModeCall::id))
            assertEquals("fast-value", follow.single().arguments["file"]?.toString()?.trim('"'))
            assertTrue(cell.advance(listOf(CodeModeResult("3", "follow-value"))) is CodeModeStep.WaitingForResults)
            assertTrue(cell.advance(listOf(CodeModeResult("2", "slow-value"))) is CodeModeStep.Completed)
        }
    }

    @Test
    fun `32 escaped maximum sized results retain ids across byte partitioned frames`() = runBlocking {
        runtime().use { host ->
            val cell = host.start(
                "const values = await Promise.all(Array.from({length: 32}, (_, i) => tools.call('Read', {i}))); " +
                    "return values.map(value => value.length).join(',');",
                setOf("Read"),
            )
            val issued = calls(cell.advance())
            assertEquals((1..32).map(Int::toString), issued.map(CodeModeCall::id))
            val outputs = "\u0001".repeat(50_000)
            val completed = cell.advance(issued.map { CodeModeResult(it.id, outputs) }) as CodeModeStep.Completed
            assertEquals(List(32) { "50000" }.joinToString(","), completed.output)
        }
    }

    private fun calls(step: CodeModeStep): List<CodeModeCall> {
        assertTrue(step is CodeModeStep.Calls)
        return (step as CodeModeStep.Calls).calls
    }

    private fun runtime() = JvmCodeModeRuntime(
        launch = HostLaunch(classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))),
    )
}
