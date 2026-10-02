package splice.provider.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.provider.codex.stream.CodeModeSwitchingSink
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSourcePart

class CodeModeStreamPortsTest : CodeModeBridgeTestSupport() {
    @Test
    fun `independent source cursors each receive the prefix and remaining terminal suffix`() = runBlocking {
        val buffer = CodeModeSourceBuffer()
        val first = buffer.view()
        val retry = buffer.view()
        assertEquals(CodeModeManual.streamingSealedGlobals, (retry as CodeModeSealedSource).sealedGlobals)
        buffer.publish("prefix")
        assertEquals(CodeModeSourcePart.Delta("prefix"), first.read())
        val waiting = async { first.read() }
        buffer.complete("prefix-suffix")
        assertEquals(CodeModeSourcePart.Complete("-suffix"), waiting.await())
        assertEquals(CodeModeSourcePart.Complete("prefix-suffix"), retry.read())
        assertEquals(CodeModeSourcePart.Complete(), first.read())
    }

    @Test
    fun `source buffering refuses changed dispatched bytes and completion cannot replace them`() {
        val buffer = CodeModeSourceBuffer()
        buffer.publish("certified")
        assertThrows(IllegalStateException::class.java) { buffer.publish("changed") }
        assertThrows(IllegalStateException::class.java) { buffer.complete("changed") }
        buffer.complete("certified suffix")
        assertThrows(IllegalStateException::class.java) { buffer.publish("certified suffix later") }
    }

    @Test
    fun `failure overrides unread source and a later failure cannot overwrite the terminal`(): Unit = runBlocking {
        val buffer = CodeModeSourceBuffer()
        val reader = buffer.view()
        buffer.publish("not yet executed")
        buffer.fail("first failure")
        buffer.fail("later failure")
        assertEquals(CodeModeSourcePart.Failed("first failure"), reader.read())
        assertThrows(IllegalStateException::class.java) { buffer.complete("not yet executed") }
    }

    @Test
    fun `a detached raw block cannot bypass the frame byte budget through metadata`(): Unit = runBlocking {
        val sink = CodeModeSwitchingSink(RecordingSink()) {}
        sink.detach()
        val block = JsonObject(
            mapOf(
                "type" to JsonPrimitive("thinking"),
                "data" to JsonPrimitive("x".repeat(CodeModeLimits.MAX_FRAME_BYTES)),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { runBlocking { sink.openRawBlock(block) } }
    }

    @Test
    fun `detached empty deltas still consume structural frame storage`() = runBlocking {
        val sink = CodeModeSwitchingSink(RecordingSink()) {}
        sink.detach()
        val index = sink.openThinking()
        var bounded = false
        var frames = 0
        while (frames++ <= CodeModeLimits.MAX_FRAME_BYTES / 20) {
            try {
                sink.thinkingDelta(index, "")
            } catch (_: IllegalArgumentException) {
                bounded = true
                break
            }
        }
        assertTrue(bounded, "zero-byte events must not create an unbounded closure queue")
    }
}
