// PORT-OF: server/src/anthropic/sse.mjs behavior pins @ pre-public-port-baseline — lazy start + ping, frame
// framing bytes, stop_reason derivation order, error path, abandon, ended idempotence.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.gateway.wire.SseEmitter
import splice.gateway.wire.TerminalMessage
import java.io.IOException
import java.util.concurrent.CancellationException

class SseEmitterTest {

    private fun collector(): Pair<MutableList<String>, SseEmitter> {
        val frames = mutableListOf<String>()
        val emitter = SseEmitter.create(
            write = { frames.add(it) },
            model = "claude-codex--gpt-5.6-sol",
            usagePayload = { u ->
                buildJsonObject {
                    put("input_tokens", u?.inputTokens ?: 0)
                    put("output_tokens", u?.outputTokens ?: 0)
                }
            },
            messageId = "msg_fixed",
        )
        return frames to emitter
    }

    @Test
    fun `start is lazy - first block emits message_start then ping then block`() = runTest {
        val (frames, e) = collector()
        assertTrue(frames.isEmpty())
        val idx = e.openText()
        assertEquals(0, idx.value)
        assertTrue(frames[0].startsWith("event: message_start\ndata: "))
        assertTrue(frames[0].contains("\"id\":\"msg_fixed\""))
        assertTrue(frames[0].endsWith("\n\n"))
        assertTrue(frames[1].startsWith("event: ping\n"))
        assertTrue(frames[2].startsWith("event: content_block_start\n"))
        assertTrue(frames[2].contains("\"index\":0"))
    }

    @Test
    fun `tool flow - eager open, json deltas on same index, close`() = runTest {
        val (frames, e) = collector()
        val tool = e.openTool(id = "toolu_9", name = "run")
        e.inputJsonDelta(tool, """{"com""")
        e.inputJsonDelta(tool, """mand":"ls"}""")
        e.closeBlock(tool)
        e.closeBlock(tool) // idempotent: second close emits nothing
        val deltas = frames.filter { it.startsWith("event: content_block_delta") }
        assertEquals(2, deltas.size)
        assertTrue(deltas.all { it.contains("\"index\":${tool.value}") && it.contains("input_json_delta") })
        assertEquals(1, frames.count { it.startsWith("event: content_block_stop") })
    }

    @Test
    fun `terminal derives stop_reason - tool_use beats max_tokens beats end_turn`() = runTest {
        suspend fun reasonFor(hasTool: Boolean, incomplete: Boolean): String {
            val (frames, e) = collector()
            e.emitTerminal(hasToolUse = hasTool, incomplete = incomplete, usage = Usage(1, 2))
            val delta = frames.first { it.startsWith("event: message_delta") }
            return Regex("\"stop_reason\":\"(\\w+)\"").find(delta)!!.groupValues[1]
        }
        assertEquals("tool_use", reasonFor(true, true))
        assertEquals("max_tokens", reasonFor(false, true))
        assertEquals("end_turn", reasonFor(false, false))
    }

    @Test
    fun `terminal is idempotent and ends with message_stop`() = runTest {
        val (frames, e) = collector()
        e.emitTerminal(false, false, Usage())
        val count = frames.size
        e.emitTerminal(false, false, Usage())
        e.emitError(ErrorType.API_ERROR, "late") // after ended: swallowed
        assertEquals(count, frames.size)
        assertTrue(frames.last().startsWith("event: message_stop"))
    }

    @Test
    fun `error path emits a single error event and seals`() = runTest {
        val (frames, e) = collector()
        e.openText()
        e.emitError(ErrorType.OVERLOADED, "upstream stalled")
        assertTrue(frames.last().startsWith("event: error\n"))
        assertTrue(frames.last().contains("overloaded_error"))
        e.emitTerminal(false, false, Usage())
        assertTrue(frames.last().startsWith("event: error\n")) // no clean stop after failure
    }

    @Test
    fun `abandon seals with nothing on the wire`() = runTest {
        val (frames, e) = collector()
        e.abandon()
        e.emitTerminal(false, false, Usage())
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `one-shot helpers - addTextBlock and addRedactedThinking`() = runTest {
        val (frames, e) = collector()
        e.addTextBlock("mirror text")
        e.addRedactedThinking("ZW5jcnlwdGVk")
        e.addTextBlock("") // no-op
        val starts = frames.filter { it.startsWith("event: content_block_start") }
        assertEquals(2, starts.size)
        assertTrue(starts[1].contains("redacted_thinking") && starts[1].contains("ZW5jcnlwdGVk"))
        assertEquals(1, frames.count { it.contains("text_delta") })
        assertEquals(2, frames.count { it.startsWith("event: content_block_stop") })
    }

    @Test
    fun `closeAll closes only open blocks in order`() = runTest {
        val (frames, e) = collector()
        val a = e.openText()
        val b = e.openThinking()
        e.closeBlock(a)
        e.closeAll()
        val stops = frames.filter { it.startsWith("event: content_block_stop") }
        assertEquals(2, stops.size)
        assertTrue(stops[1].contains("\"index\":${b.value}"))
    }

    @Test
    fun `signature delta rides content_block_delta between thinking deltas and block stop`() = runTest {
        val (frames, e) = collector()
        val t = e.openThinking()
        e.thinkingDelta(t, "reason")
        e.signatureDelta(t, "sig-abc")
        e.closeBlock(t)
        val sig = frames.single { it.contains("signature_delta") }
        assertTrue(sig.startsWith("event: content_block_delta"))
        assertTrue(sig.contains("\"index\":${t.value}"))
        assertTrue(sig.contains("\"signature\":\"sig-abc\""))
        // ordering: thinking_delta before signature_delta before content_block_stop
        val idxThinking = frames.indexOfFirst { it.contains("thinking_delta") }
        val idxSig = frames.indexOfFirst { it.contains("signature_delta") }
        val idxStop = frames.indexOfFirst { it.startsWith("event: content_block_stop") }
        assertTrue(idxThinking < idxSig && idxSig < idxStop)
    }

    @Test
    fun `signature delta to a closed or unknown index is a no-op`() = runTest {
        val (frames, e) = collector()
        val t = e.openThinking()
        e.closeBlock(t)
        val before = frames.size
        e.signatureDelta(t, "late-sig") // block already closed
        e.signatureDelta(WireBlockIndex(99), "never-opened") // unknown index
        assertEquals(before, frames.size)
        assertTrue(frames.none { it.contains("signature_delta") })
    }

    @Test
    fun `non-stream terminal message derives the same stop reasons`() {
        val msg = SseEmitter.terminalMessageJson(
            TerminalMessage(
                id = "msg_1",
                model = "m",
                content = emptyList(),
                hasToolUse = false,
                incomplete = true,
                usagePayload = buildJsonObject { put("input_tokens", 1) },
            ),
        )
        assertEquals("\"max_tokens\"", msg["stop_reason"].toString())
    }

    @Test
    fun `cancellation mid-terminal releases the claim so emitError can still seal`() = runTest {
        // The stranded-ENDING hole (review 2026-07-22): a head-stop cancel lands while the
        // terminal frames are being written; the follow-up cancellation-seal emitError
        // (TurnDriver.driveSealingCancellation) must still put an honest error frame on the
        // wire — not no-op against a held claim and leave the client a truncated 200.
        val frames = mutableListOf<String>()
        var failOnce = true
        val emitter = SseEmitter.create(
            write = { frame ->
                if (failOnce && frame.startsWith("event: message_delta")) {
                    failOnce = false
                    throw CancellationException("head stop mid-terminal")
                }
                frames.add(frame)
            },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            messageId = "msg_fixed",
        )
        var cancelled: CancellationException? = null
        try {
            emitter.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage(1, 2))
        } catch (e: CancellationException) {
            cancelled = e
        }
        assertTrue(cancelled != null, "mid-terminal cancellation must propagate")
        assertTrue(!emitter.hasEnded, "a cancelled terminal must not read as ended")
        emitter.emitError(ErrorType.OVERLOADED, "turn cancelled — retry")
        assertTrue(emitter.hasEnded, "the seal after a cancelled terminal must land")
        assertTrue(frames.any { it.startsWith("event: error") && it.contains("turn cancelled") })
    }

    @Test
    fun `IOException mid-terminal stays sealed ENDED so a follow-up emitError no-ops`() = runTest {
        // The branch-introduced regression: a client disconnecting on the second terminal frame
        // (message_stop) threw IOException; the old finally reverted the seal to OPEN, so the
        // downstream emitConnReset → emitError re-attempted a doomed write that threw again and
        // escaped its recordPerf/health telemetry. Client-gone must stay ENDED (unlike a
        // cancellation, which releases), leaving a follow-up emitError a clean no-op.
        val frames = mutableListOf<String>()
        val emitter = SseEmitter.create(
            write = { frame ->
                if (frame.startsWith("event: message_stop")) {
                    throw IOException("client gone mid-terminal")
                }
                frames.add(frame)
            },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            messageId = "msg_fixed",
        )
        var thrown: IOException? = null
        try {
            emitter.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage(1, 2))
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue(thrown != null, "a client-gone IOException mid-terminal must propagate")
        assertTrue(emitter.hasEnded, "a client-gone terminal stays sealed ENDED")
        val before = frames.size
        emitter.emitError(ErrorType.API_ERROR, "conn reset")
        assertEquals(before, frames.size) // emitError no-ops: nothing new on the wire
    }
}
