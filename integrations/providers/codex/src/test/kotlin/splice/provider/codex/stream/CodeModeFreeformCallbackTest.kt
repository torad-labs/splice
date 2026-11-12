package splice.provider.codex.stream

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.stream.native.CodeModeSearchCell
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell

class CodeModeFreeformCallbackTest {
    @Test
    fun `valid freeform input reaches the object client boundary without changing its native payload`() = runTest {
        val input = JsonPrimitive("opaque raw input\n☃ \"raw\"")
        val plain = CodeModeCall("plain", "Echo", JsonObject(emptyMap()))
        val freeform = CodeModeCall(
            "raw",
            "Raw",
            JsonObject(emptyMap()),
            rawInput = input,
            kind = CodeModeManual.Kind.FREEFORM,
        )
        val cell = CodeModeSearchCell(
            CallbackCell(listOf(plain, freeform)),
            CodexCodeModeBridge.Turn("session", "conversation", "fixture", setOf("Echo", "Raw")),
        )
        val observed = assertInstanceOf(CodeModeStep.Calls::class.java, cell.observe(0))
        assertEquals(listOf("Echo", "Raw"), observed.calls.map(CodeModeCall::name))
        assertSame(plain, observed.calls.first())
        assertEquals(freeform.copy(arguments = JsonObject(mapOf("input" to input))), observed.calls.last())
    }

    private class CallbackCell(private val calls: List<CodeModeCall>) : NativeCodeModeCell {
        override val cellId = "fixture"
        override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = observe(0)
        override suspend fun submitResults(results: List<CodeModeResult>) = Unit
        override suspend fun observe(yieldTimeMs: Long): CodeModeStep = CodeModeStep.Calls(calls)
        override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("")
        override fun close() = Unit
    }
}
