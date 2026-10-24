package splice.codemode

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.codemode.host.HostLaunch
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.CodeModeStep

class CodeModeArgumentRecoveryTest {
    @Test
    fun `caught serialization errors allow a later tool call in both execution modes`() = runBlocking {
        val source = """
            let rejected = 0;
            for (const value of [null, [], true, 42, "bad"]) {
              try { await tools.Read({toJSON() { return value; }}); }
              catch (_) { rejected++; }
            }
            const result = await tools.Read({file: "good"});
            text(rejected + ":" + result);
        """.trimIndent()
        val classpath = checkNotNull(System.getProperty("codeMode.testClasspath"))
        JvmCodeModeRuntime(launch = HostLaunch(classpath = classpath)).use { runtime ->
            for (streaming in listOf(false, true)) {
                val cell = if (streaming) {
                    runtime.startStreaming(CodeModeSource { CodeModeSourcePart.Complete(source) }, setOf("Read"))
                } else {
                    runtime.start(source, setOf("Read"))
                }
                cell.use {
                    val call = (it.advance() as CodeModeStep.Calls).calls.single()
                    assertEquals("Read", call.name)
                    assertEquals("\"good\"", call.arguments["file"].toString())
                    val completed = it.advance(listOf(CodeModeResult(call.id, "ok"))) as CodeModeStep.Completed
                    assertNull(completed.error)
                    assertEquals("5:ok", completed.output)
                }
            }
        }
    }
}
