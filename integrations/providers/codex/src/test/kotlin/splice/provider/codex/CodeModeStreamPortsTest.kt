package splice.provider.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.SpliceNotice
import splice.provider.codex.stream.CodeModeClientStepSink
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.provider.codex.stream.CodeModeSwitchingSink
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.sse.WireSink

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

    /** V4-456: a dispatched tool call ends the client step mid-script. The live script block is signed at
     *  that cut, so Claude Code never replays the script as reasoning, and the script goes on in the next
     *  client step. */
    @Test
    fun `a dispatched call signs the live script at the cut and the script continues in the next step`() =
        runBlocking {
            val first = EventSink()
            val round = CodeModeSwitchingSink(first) {}
            val script = round.openNotice()
            round.thinkingDelta(script, "const a = ")
            CodeModeClientStepSink(round, first).openTool("call-1", "Read")
            round.thinkingDelta(script, "await tools.Read();")
            val second = EventSink()
            round.attach(second)
            round.signatureDelta(script, SpliceNotice.SIGNATURE)
            round.closeBlock(script)
            val signed = "sig#0:${SpliceNotice.SIGNATURE}"
            assertEquals(
                listOf("openThinking#0", "think#0:const a = ", signed, "close#0", "openTool#1(call-1,Read)"),
                first.events,
            )
            assertEquals(listOf("openThinking#0", "think#0:await tools.Read();", signed, "close#0"), second.events)
        }

    @Test
    fun `a model thinking block cut by a dispatched call is not signed as splice's own`() = runBlocking {
        val first = EventSink()
        val round = CodeModeSwitchingSink(first) {}
        val reasoning = round.openThinking()
        round.thinkingDelta(reasoning, "model text")
        round.detach()
        assertEquals(listOf("openThinking#0", "think#0:model text", "close#0"), first.events)
    }

    @Test
    fun `a closing signature never reopens a live script that the cut already signed`() = runBlocking {
        val round = CodeModeSwitchingSink(EventSink()) {}
        val script = round.openNotice()
        round.thinkingDelta(script, "return 1;")
        round.detach()
        val next = EventSink()
        round.attach(next)
        round.signatureDelta(script, SpliceNotice.SIGNATURE)
        round.closeBlock(script)
        assertEquals(emptyList<String>(), next.events, "an empty signed block would be drawn in the next step")
    }

    /** A 65,536-character script sent a character a delta outweighs the detached budget in envelopes. It
     *  is dropped past its own budget, and model output keeps the whole budget it had. */
    @Test
    fun `live script past the detached byte budget is dropped and never spends the model's budget`() =
        runBlocking {
            val round = CodeModeSwitchingSink(EventSink()) {}
            round.detach()
            val script = round.openNotice()
            repeat(CodeModeLimits.MAX_FRAME_BYTES / 20) { round.thinkingDelta(script, "x") }
            val text = round.openText()
            round.textDelta(text, "y".repeat(CodeModeLimits.MAX_FRAME_BYTES / 2))
            val next = EventSink()
            round.attach(next)
            val shown = next.events.count { it == "think#0:x" }
            assertTrue(shown in 1 until CodeModeLimits.MAX_FRAME_BYTES / 20, "the script was not cut: $shown")
            assertTrue(next.events.contains("text#1:" + "y".repeat(CodeModeLimits.MAX_FRAME_BYTES / 2)))
        }

    private class EventSink : WireSink {
        val events = mutableListOf<String>()
        private var next = 0

        private fun opened(event: String): WireBlockIndex {
            val index = WireBlockIndex(next++)
            events += "$event#${index.value}"
            return index
        }

        override suspend fun openText() = opened("openText")
        override suspend fun openThinking() = opened("openThinking")

        override suspend fun openTool(id: String, name: String): WireBlockIndex {
            val index = WireBlockIndex(next++)
            events += "openTool#${index.value}($id,$name)"
            return index
        }

        override suspend fun textDelta(index: WireBlockIndex, text: String) {
            events += "text#${index.value}:$text"
        }

        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
            events += "think#${index.value}:$thinking"
        }

        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
            events += "json#${index.value}:$partialJson"
        }

        override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
            events += "sig#${index.value}:$signature"
        }

        override suspend fun closeBlock(index: WireBlockIndex) {
            events += "close#${index.value}"
        }

        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }
}
