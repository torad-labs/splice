package splice.provider.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.reasoning.ReasoningReplay
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.provider.codex.stream.CodeModeSwitchingSink
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.sse.WireSink

class CodeModeStreamPortsTest : CodeModeBridgeTestSupport() {
    /** A script runs only once its whole item arrived (Oct 23): a streamed prefix reaches no cursor, and the
     *  item completion hands each cursor the whole source in one part. */
    @Test
    fun `a source cursor gets nothing while the item streams and the whole item on completion`() = runBlocking {
        val buffer = CodeModeSourceBuffer()
        val first = buffer.view()
        val retry = buffer.view()
        buffer.publish("prefix")
        val waiting = async { first.read() }
        yield()
        assertFalse(waiting.isCompleted, "a streamed prefix reaches no cursor")
        buffer.complete("prefix-suffix")
        assertEquals(CodeModeSourcePart.Complete("prefix-suffix"), waiting.await())
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

    /** A folding head buffers a round's text until the round proves clean. A websocket round that fails before any
     *  client frame is re-served over SSE on the same code-mode sink, so the discard must reach the buffer through
     *  the code-mode wrapper: the client then sees only the SSE answer, and the round reports only that as delivered. */
    @Test
    fun `a discarded websocket draft never reaches a folding client through the code-mode sink`() = runBlocking {
        val shown = mutableListOf<String>()
        val folding = object : WireSink by RecordingSink() {
            val pending = mutableListOf<String>()

            override suspend fun textDelta(index: WireBlockIndex, text: String) {
                pending += text
            }

            override fun discard() {
                pending.clear()
            }

            fun flush() {
                shown += pending
                pending.clear()
            }
        }
        val round = CodeModeSwitchingSink(folding) {}
        val draft = round.openText()
        round.textDelta(draft, "draft")
        round.discard()
        val answer = round.openText()
        round.textDelta(answer, "answer")
        folding.flush()
        assertEquals(listOf("answer"), shown)
        assertEquals("answer", round.detach())
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
    fun `oversized prose still reaches the client but is not remembered as complete continuity`() = runBlocking {
        val target = EventSink()
        val round = CodeModeSwitchingSink(target) {}
        val block = round.openText()
        val prose = "é".repeat(CodeModeLimits.MAX_FRAME_BYTES / 2)
        round.textDelta(block, prose)
        round.textDelta(block, "é")
        assertNull(round.detach(), "a truncated continuity must never be staged as complete")
        assertEquals(listOf("openText#0", "text#0:$prose", "text#0:é", "close#0"), target.events)
        round.attach(RecordingSink())
        round.textDelta(block, "later")
        assertNull(round.detach(), "a later step cannot make the overflowed memory complete")
    }

    @Test
    fun `native witnesses contain only the envelopes delivered on each attachment`() = runBlocking {
        val first = EventSink()
        val round = CodeModeSwitchingSink(first) {}
        val a = nativeEnvelope("first")
        val b = nativeEnvelope("second")
        round.addRedactedThinking(a)
        assertEquals("", round.detach())
        assertEquals(listOf(ReasoningReplay.decodeReasoningEnvelope(a)), round.deliveredNative)
        round.addRedactedThinking(b)
        assertEquals(listOf("native:$a"), first.events, "a detached write has not reached the client")
        val second = EventSink()
        round.attach(second)
        round.detach()
        assertEquals(listOf("native:$b"), second.events)
        assertEquals(listOf(ReasoningReplay.decodeReasoningEnvelope(b)), round.deliveredNative)
        round.attach(EventSink())
        round.detach()
        assertEquals(emptyList<JsonObject>(), round.deliveredNative, "an attachment cannot inherit earlier witnesses")
    }

    @Test
    fun `native witness overflow shares the prose budget and never blocks delivery`() = runBlocking {
        val target = EventSink()
        val round = CodeModeSwitchingSink(target) {}
        val envelope = nativeEnvelope("overflow")
        round.addTextBlock("x".repeat(CodeModeLimits.MAX_FRAME_BYTES))
        round.addRedactedThinking(envelope)
        assertEquals(listOf("native:$envelope"), target.events, "overflow still delivers the envelope")
        assertNull(round.detach(), "native overflow also invalidates incomplete prose capture")
        assertNull(round.deliveredNative, "an incomplete native witness cannot own a partial echo")
        round.attach(EventSink())
        round.addRedactedThinking(envelope)
        round.detach()
        assertNull(round.deliveredNative, "another attachment cannot reset the cumulative budget")
    }

    private fun nativeEnvelope(id: String): String = checkNotNull(
        ReasoningReplay.encodeReasoningEnvelope(
            Json.parseToJsonElement(
                """{"type":"reasoning","id":"synthetic-$id","encrypted_content":"synthetic-$id","summary":[]}""",
            ) as JsonObject,
        ),
    )

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
        override suspend fun addRedactedThinking(data: String) {
            events += "native:$data"
        }
    }
}
