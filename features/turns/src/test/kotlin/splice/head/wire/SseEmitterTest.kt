// PORT-OF: server/src/anthropic/sse.mjs behavior pins @ pre-public-port-baseline — lazy start + ping, frame
// framing bytes, stop_reason derivation order, error path, abandon, ended idempotence.
package splice.head.wire

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.core.failure.Stall
import splice.core.index.WireBlockIndex
import splice.core.perf.TurnPerf
import splice.core.turn.Usage
import splice.core.util.ElapsedClock
import splice.head.testVoice
import splice.head.turn.stream.PendingSse
import java.io.IOException
import java.io.StringWriter
import java.util.concurrent.CancellationException

class SseEmitterTest {

    private val emitters = SseEmitterFactory()

    /** What an error frame tells the client: its type and its words. */
    private fun errorOf(frame: String): Pair<String, String> {
        val data = frame.substringAfter("data: ").trim()
        val error = Json.parseToJsonElement(data).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    /** The line [cause] ends a turn with, where content has [reached] the client or not. */
    private fun lineAt(cause: FailureCause, reached: Boolean) =
        testVoice.ending(cause, Position(if (reached) Moment.MID_CONTENT else Moment.COMMITTED, streamBegan = reached))

    private fun collector(): Pair<MutableList<String>, SseEmitter> {
        val frames = mutableListOf<String>()
        val emitter = emitters.create(
            write = { frames.add(it) },
            model = "claude-codex--gpt-5.6-sol",
            usagePayload = { u ->
                buildJsonObject {
                    put("input_tokens", u?.inputTokens ?: 0)
                    put("output_tokens", u?.outputTokens ?: 0)
                }
            },
            streaming = StreamWiring(messageId = "msg_fixed"),
            voice = testVoice,
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

    /** The heartbeat (ClientChannel's pinger on a silent wire): nothing before message_start, a ping
     *  event after it, nothing once the turn has ended. */
    @Test
    fun `heartbeat - silent before start, a ping event while open, silent after the terminal`() = runTest {
        val (frames, e) = collector()
        e.heartbeat()
        assertTrue(frames.isEmpty(), "no ping ahead of message_start: $frames")
        e.ensureStarted()
        val opened = frames.size
        e.heartbeat()
        assertEquals(opened + 1, frames.size)
        assertEquals("event: ping\ndata: {\"type\":\"ping\"}\n\n", frames.last())
        e.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())
        val ended = frames.size
        e.heartbeat()
        assertEquals(ended, frames.size, "an ended turn writes nothing more")
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
        e.emitError(FailureCause.Provider.Unrecognized(clientError = false)) // after ended: swallowed
        assertEquals(count, frames.size)
        assertTrue(frames.last().startsWith("event: message_stop"))
    }

    @Test
    fun `error path emits a single error event and seals`() = runTest {
        val (frames, e) = collector()
        e.openText()
        e.emitError(FailureCause.Path.Stalled(Stall.IDLE))
        assertTrue(frames.last().startsWith("event: error\n"))
        assertTrue(frames.last().contains("overloaded_error"))
        e.emitTerminal(false, false, Usage())
        assertTrue(frames.last().startsWith("event: error\n")) // no clean stop after failure
    }

    @Test
    fun `a permanent failure is an error event without assistant text or a clean stop`() = runTest {
        val (frames, e) = collector()
        val cause = FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_STATE)
        e.emitError(cause)
        assertTrue(frames.single().startsWith("event: error\ndata: "), "$frames")
        assertEquals("invalid_request_error" to lineAt(cause, reached = true).text, errorOf(frames.single()))
        assertTrue(!e.endedCleanly)
        e.emitTerminal(false, false, Usage())
        assertTrue(frames.last().startsWith("event: error"))
    }

    @Test
    fun `a failure after a streamed tool_use still ends as an error`() = runTest {
        val (frames, e) = collector()
        val idx = e.openTool("toolu_1", "Read")
        e.inputJsonDelta(idx, "{}")
        e.closeBlock(idx)
        e.emitError(FailureCause.Provider.Refused(RefusedBy.FILTER))
        assertTrue(frames.last().startsWith("event: error"))
        assertTrue(frames.none { it.startsWith("event: message_delta") || it.startsWith("event: message_stop") })
        assertTrue(frames.none { it.contains("text_delta") })
    }

    /** Failure-handling slice 2: the emitter is the one place an error frame is written, and its words are the line
     *  its turn's voice composes for the cause where the stream stands when the frame is written, never a caller's. */
    @Test
    fun `an error frame says the line its turn voice composes for the cause where the stream stands`() = runTest {
        val cause = FailureCause.Provider.ServerError(ServerFault.INTERNAL)
        val said = listOf(false, true).map { reached ->
            val frames = mutableListOf<String>()
            val e = emitters.create(
                write = { frames.add(it) },
                model = "m",
                usagePayload = { buildJsonObject { } },
                streaming = StreamWiring(contentReached = { reached }),
                voice = testVoice,
            )
            assertEquals(null, e.lineWritten, "no line is written before the ending")
            assertEquals(lineAt(cause, reached), e.lineFor(cause), "the line the ending would write now")
            e.emitError(cause)
            val (_, words) = errorOf(frames.single { it.startsWith("event: error") })
            assertEquals(lineAt(cause, reached).text, words, "content reached the client: $reached")
            assertEquals(lineAt(cause, reached), e.lineWritten)
            words
        }
        assertTrue(said.distinct().size == 2, "the line says where the answer stood: $said")
    }

    @Test
    fun `an error after the turn ended writes no line`() = runTest {
        val (frames, e) = collector()
        e.emitTerminal(false, false, Usage())
        e.emitError(FailureCause.Path.Stalled(Stall.IDLE))
        assertTrue(frames.none { it.startsWith("event: error") }, "$frames")
        assertEquals(null, e.lineWritten)
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
        val msg = SseEmitter.TerminalEnvelope().terminalMessageJson(
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
        val emitter = emitters.create(
            write = { frame ->
                if (failOnce && frame.startsWith("event: message_delta")) {
                    failOnce = false
                    throw CancellationException("head stop mid-terminal")
                }
                frames.add(frame)
            },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            streaming = StreamWiring(messageId = "msg_fixed"),
            voice = testVoice,
        )
        var cancelled: CancellationException? = null
        try {
            emitter.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage(1, 2))
        } catch (e: CancellationException) {
            cancelled = e
        }
        assertTrue(cancelled != null, "mid-terminal cancellation must propagate")
        assertTrue(!emitter.hasEnded, "a cancelled terminal must not read as ended")
        emitter.emitError(FailureCause.Splice.Internal())
        assertTrue(emitter.hasEnded, "the seal after a cancelled terminal must land")
        val (_, words) = errorOf(frames.single { it.startsWith("event: error") })
        assertEquals(lineAt(FailureCause.Splice.Internal(), reached = true).text, words)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `the production client channel seals a delivered error before flush cancellation returns`() = runTest {
        val writing = Job()
        val writer = object : StringWriter() {
            override fun flush() {
                writing.cancel()
            }
        }
        val perf = TurnPerf { testScheduler.currentTime }
        val pending = PendingSse(perf, ElapsedClock { testScheduler.currentTime }, null, null)
        pending.finish()
        pending.attach(writer)
        val emitter = emitters.create(
            write = pending::model,
            model = "synthetic",
            usagePayload = { buildJsonObject { } },
            streaming = StreamWiring(messageId = "synthetic-message"),
            voice = testVoice,
        )
        try {
            withContext(writing) {
                emitter.emitError(FailureCause.Path.Stalled(Stall.IDLE))
            }
        } catch (_: CancellationException) {
            // PendingSseWriter waits before writing; its synchronous flush cannot reopen a delivered frame.
        }
        emitter.emitError(FailureCause.Splice.Internal())
        val errors = writer.toString().split("\n\n").filter { it.startsWith("event: error") }
        val watchdog = lineAt(FailureCause.Path.Stalled(Stall.IDLE), reached = true)
        assertEquals(listOf(watchdog.text), errors.map { errorOf(it).second })
        // The trace reads the line the client read: the one delivered, not the seal's dropped second.
        assertEquals(watchdog, emitter.lineWritten)
        assertTrue(emitter.hasEnded)
    }

    @Test
    fun `IOException mid-terminal stays sealed ENDED so a follow-up emitError no-ops`() = runTest {
        // The branch-introduced regression: a client disconnecting on the second terminal frame
        // (message_stop) threw IOException; the old finally reverted the seal to OPEN, so the
        // downstream emitConnReset → emitError re-attempted a doomed write that threw again and
        // escaped its recordPerf/health telemetry. Client-gone must stay ENDED (unlike a
        // cancellation, which releases), leaving a follow-up emitError a clean no-op.
        val frames = mutableListOf<String>()
        val emitter = emitters.create(
            write = { frame ->
                if (frame.startsWith("event: message_stop")) {
                    throw IOException("client gone mid-terminal")
                }
                frames.add(frame)
            },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            streaming = StreamWiring(messageId = "msg_fixed"),
            voice = testVoice,
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
        emitter.emitError(FailureCause.Provider.Unrecognized(clientError = false))
        assertEquals(before, frames.size) // emitError no-ops: nothing new on the wire
    }

    // DR-119 (review 2026-08-31): the PRODUCTION sink's raw verbs, driven through the emitter a
    // client actually receives. The passthrough suite proves the TRANSLATOR calls openRawBlock and
    // rawDelta, but it injects a test Rec that implements both itself — so deleting BOTH real
    // WireBlockWriter overrides compiled clean and left that wall green while the real emitter fell
    // back to WireSink's defaults (openRawBlock returning null, rawDelta a no-op) and silently
    // swallowed every forwarded block and delta. Four claims, one per way that can break: the
    // content_block payload rides verbatim, a raw delta reaches the wire verbatim, closeBlock ends
    // it, and the L3 open-guard still refuses a delta to a block that is not open.
    @Test
    fun `raw block verbs forward verbatim through the production emitter`() = runTest {
        val deltaEvent = "event: content_block_delta"
        val (frames, e) = collector()
        val raw = e.openRawBlock(
            buildJsonObject {
                put("type", "server_tool_use")
                put("id", "srvtoolu_119")
                put("name", "web_search")
            },
        )
        assertTrue(raw != null, "the production sink must forward raw blocks, not WireSink's null default")
        val start = frames.first { it.startsWith("event: content_block_start") }
        assertTrue(start.contains("\"index\":${raw!!.value}"), "the raw block owns the index it returned: $start")
        assertTrue(start.contains("\"type\":\"server_tool_use\""), "content_block type rides verbatim: $start")
        assertTrue(start.contains("\"id\":\"srvtoolu_119\""), "content_block id rides verbatim: $start")
        assertTrue(start.contains("\"name\":\"web_search\""), "content_block name rides verbatim: $start")

        e.rawDelta(
            raw,
            buildJsonObject {
                put("type", "citations_delta")
                put("cited_text", "verbatim-119")
            },
        )
        val deltas = frames.filter { it.startsWith(deltaEvent) }
        assertEquals(1, deltas.size, "rawDelta must reach the wire — WireSink's default no-op swallows it")
        assertTrue(deltas[0].contains("\"type\":\"citations_delta\""), "the delta rides verbatim: ${deltas[0]}")
        assertTrue(deltas[0].contains("\"cited_text\":\"verbatim-119\""), "delta payload verbatim: ${deltas[0]}")

        e.closeBlock(raw)
        assertEquals(1, frames.count { it.contains("content_block_stop") }, "closeBlock ends the raw block")

        // The open-guard, both shapes of not-open: an index removed from `open` by the close above,
        // and one that was never opened at all. Neither may reach the wire (L3 block-pairing).
        e.rawDelta(raw, buildJsonObject { put("type", "citations_delta") })
        e.rawDelta(WireBlockIndex(raw.value + 1), buildJsonObject { put("type", "citations_delta") })
        assertEquals(1, frames.count { it.startsWith(deltaEvent) }, "a rawDelta to a non-open block is a no-op")
    }
}
