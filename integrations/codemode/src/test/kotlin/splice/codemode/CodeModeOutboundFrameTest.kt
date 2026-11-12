package splice.codemode

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.host.HostLaunch
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeProtocol
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep

class CodeModeOutboundFrameTest {
    private val testClasspath = checkNotNull(System.getProperty("codeMode.testClasspath"))

    @Test
    fun `queued calls stay in id order when the encoded call frame reaches its byte ceiling`() = runBlocking {
        JvmCodeModeRuntime(launch = HostLaunch(classpath = testClasspath)).use { runtime ->
            val payload = "x".repeat(ARGUMENT_BYTES)
            val cell = runtime.start(
                source = """
                    const reads = await Promise.allSettled(
                      Array.from({length: 9}, () => tools.call("Read", {blob: "x".repeat($ARGUMENT_BYTES)})));
                    const shell = await tools.call("Bash", {command: "ls"});
                    return reads.map(r => r.status + ":" + r.value).join(",") + "|" + shell;
                """.trimIndent(),
                tools = setOf("Read", "Bash"),
            )

            val first = calls(cell.advance())
            assertTrue(first.isNotEmpty())
            assertTrue(first.size < READ_COUNT)
            assertTrue(first.all { it.name == "Read" && it.argumentBlob() == payload })
            assertTrue(
                CodeModeProtocol.encodeFrame(CodeModeWire.callsFrame(first)).size <= CodeModeWire.maxFrameBytes,
            )

            val second = calls(cell.advance(first.map { CodeModeResult(it.id, "r${it.id}") }))
            assertTrue(second.all { it.name == "Read" && it.argumentBlob() == payload })
            val reads = first + second
            assertEquals((1..READ_COUNT).map(Int::toString), reads.map(CodeModeCall::id))
            assertTrue(
                CodeModeProtocol.encodeFrame(CodeModeWire.callsFrame(reads)).size > CodeModeWire.maxFrameBytes,
            )

            val shell = calls(cell.advance(second.map { CodeModeResult(it.id, "r${it.id}") }))
            assertEquals(listOf("10" to "Bash"), shell.map { it.id to it.name })
            val completed = cell.advance(listOf(CodeModeResult("10", "listed"))) as CodeModeStep.Completed
            assertEquals((1..READ_COUNT).joinToString(",") { "fulfilled:r$it" } + "|listed", completed.output)
        }
    }

    private fun CodeModeCall.argumentBlob(): String? = (arguments["blob"] as? JsonPrimitive)?.content

    private fun calls(step: CodeModeStep): List<CodeModeCall> =
        (step as? CodeModeStep.Calls)?.calls ?: error("Expected a code-mode tool-call batch")

    private companion object {
        const val ARGUMENT_BYTES = 160_000
        const val READ_COUNT = 9
    }
}
