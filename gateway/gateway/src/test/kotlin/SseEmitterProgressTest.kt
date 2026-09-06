// NEW: the keepalive pinger's half of the SSE emitter, split out of SseEmitterTest when that class
// hit detekt's LargeClass ceiling (2026-09-06). One seam, one file: everything here is about the
// two verbs the PINGER calls on its own coroutine — heartbeat and progress — and the invariants
// that make a second writer on one socket legal. The turn's own frame grammar stays next door.
//
// The barrier in these tests is always the test's own write sink: a FrameWrite that completes a
// deferred and then awaits one, which parks the calling coroutine mid-write with whatever lock it
// holds still held. That is what makes a two-coroutine interleaving deterministic on runTest's
// single-threaded dispatcher without a line of instrumentation in production code.
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.Usage
import splice.gateway.wire.SseEmitter
import splice.gateway.wire.SseEmitterFactory

class SseEmitterProgressTest {

    private val emitters = SseEmitterFactory()

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
            messageId = "msg_fixed",
        )
        return frames to emitter
    }

    @Test
    fun `progress - silent before start, ONE thinking block for the turn, closed by the terminal`() = runTest {
        val (frames, e) = collector()
        e.progress("a")
        assertTrue(frames.isEmpty(), "no status line ahead of message_start: $frames")
        e.ensureStarted()
        val opened = frames.size

        e.progress("first")
        assertEquals(opened + 2, frames.size, "the first line opens the block and writes into it")
        assertTrue(frames[opened].startsWith("event: content_block_start"), frames[opened])
        assertTrue(frames[opened].contains("\"type\":\"thinking\""), frames[opened])
        assertTrue(frames[opened + 1].contains("\"thinking\":\"first\""), frames[opened + 1])

        e.progress("second")
        assertEquals(opened + 3, frames.size, "every later line is one delta on the SAME block")
        assertEquals(
            1,
            frames.count { it.startsWith("event: content_block_start") },
            "a turn gets one status block, not one per line: $frames",
        )

        // A model block minted after ours takes the NEXT index — one sequence, two writers.
        val text = e.openText()
        assertEquals(1, text.value, "the status block took index 0, so the model's takes 1")

        e.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage())
        val stop = frames.indexOfFirst { it.startsWith("event: content_block_stop") && it.contains("\"index\":0") }
        val messageDelta = frames.indexOfFirst { it.startsWith("event: message_delta") }
        assertTrue(stop in 0 until messageDelta, "the status block closes before the ending: $frames")

        val ended = frames.size
        e.progress("after")
        assertEquals(ended, frames.size, "an ended turn writes nothing more")
    }

    /** A heartbeat that CLEARED the entry seal check and is then queued behind the ending writes
     *  nothing once it finally holds the lock. The invariant is "no queued heartbeat write after the
     *  seal reaches ENDING", not "nothing after message_stop": the second is schedule-dependent, the
     *  first is the thing heartbeat's own docstring promises.
     *
     *  Deterministic and non-vacuous by construction on the test dispatcher. The first, uncontended
     *  heartbeat proves the verb DOES write while OPEN, so a later count of one cannot pass merely
     *  because the verb is dead. The second is launched while the seal is still OPEN — so it clears
     *  the entry read — and its only remaining suspension point is acquiring the mutex the blocked
     *  progress line is holding, so runCurrent leaves it provably queued there. The barrier is the
     *  test's own write sink; nothing was added to production code to pause it. */
    @Test
    fun `heartbeat - one queued behind the ending writes no ping after ENDING`() = runTest {
        val model = mutableListOf<String>()
        val pinger = mutableListOf<String>()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val e = emitters.create(
            write = { model.add(it) },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            messageId = "msg_fixed",
            progressWrite = { frame ->
                if (frame.startsWith("event: content_block_start")) {
                    holding.complete(Unit)
                    release.await()
                }
                pinger.add(frame)
            },
        )
        e.ensureStarted()

        e.heartbeat()
        assertEquals(1, pinger.count { it.startsWith("event: ping") }, "a heartbeat writes while OPEN: $pinger")

        val line = launch { e.progress("holding") }
        holding.await()
        val queued = launch { e.heartbeat() }
        testScheduler.runCurrent()
        val ending = launch { e.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage()) }
        testScheduler.runCurrent()

        release.complete(Unit)
        line.join()
        queued.join()
        ending.join()

        assertEquals(
            1,
            pinger.count { it.startsWith("event: ping") },
            "the queued heartbeat resolved after ENDING and must have written nothing: $pinger",
        )
        assertTrue(model.any { it.startsWith("event: message_stop") }, "the turn still ended: $model")
    }

    /** The pinger must not ASSEMBLE its frames on the turn's writer: SseFrameWriter reuses one
     *  StringBuilder across every frame, so two coroutines in it interleave two frames into one
     *  buffer. That race needs a multithreaded dispatcher and cannot be reproduced deterministically,
     *  so what is pinned here is the STRUCTURE that removes it rather than the symptom — the pinger
     *  owns its own writer and its own port. Collapsing it back onto the turn's writer lands these
     *  frames on the model's sink and turns this red. */
    @Test
    fun `the pinger's frames never travel the turn's writer`() = runTest {
        val model = mutableListOf<String>()
        val pinger = mutableListOf<String>()
        val e = emitters.create(
            write = { model.add(it) },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            messageId = "msg_fixed",
            progressWrite = { pinger.add(it) },
        )

        e.ensureStarted()
        val opener = model.size
        assertTrue(pinger.isEmpty(), "the opener is the turn's own, not the pinger's: $pinger")

        e.heartbeat()
        e.progress("holding")
        assertEquals(opener, model.size, "nothing the pinger wrote reached the turn's writer: $model")
        assertEquals(3, pinger.size, "its ping, its block, its line — all on its own port: $pinger")
        assertTrue(pinger[0].startsWith("event: ping"), pinger[0])

        val idx = e.openText()
        e.textDelta(idx, "hi")
        assertEquals(3, pinger.size, "and the model's content never travels the pinger's: $pinger")
        assertTrue(model.size > opener, "it went to the turn's writer instead: $model")
    }

    /** The pinger's gate is "the opener is ON THE WIRE", not "the opener has been claimed". The
     *  latch inside MessageStart flips BEFORE message_start is written — it is there for
     *  re-entrancy — so a pinger reading THAT could put its frame ahead of the opener, which is not
     *  a stream any client can parse. */
    @Test
    fun `progress - silent until the opener is on the wire, not merely latched`() = runTest {
        val frames = mutableListOf<String>()
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val e = emitters.create(
            write = { frame ->
                if (frame.startsWith("event: message_start")) {
                    opening.complete(Unit)
                    release.await()
                }
                frames.add(frame)
            },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            messageId = "msg_fixed",
        )

        val opener = launch { e.ensureStarted() }
        opening.await()
        val line = launch { e.progress("holding") }
        testScheduler.runCurrent()
        assertTrue(frames.isEmpty(), "nothing may reach the client ahead of message_start: $frames")

        release.complete(Unit)
        opener.join()
        line.join()
        assertTrue(frames[0].startsWith("event: message_start"), "the opener is still first: $frames")
    }

    /** The one interleaving the seam exists for: the keepalive pinger is INSIDE a status-line write
     *  when the turn ends. The ending must not step over it — a message_delta emitted past a block
     *  the pinger still has open is a stream the client cannot parse. */
    @Test
    fun `progress - an ending waits for a status line already in flight, then closes its block`() = runTest {
        val frames = mutableListOf<String>()
        val opening = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val e = emitters.create(
            write = { frame ->
                if (frame.startsWith("event: content_block_start") && frame.contains("\"type\":\"thinking\"")) {
                    opening.complete(Unit)
                    release.await()
                }
                frames.add(frame)
            },
            model = "m",
            usagePayload = { buildJsonObject { put("input_tokens", 0) } },
            messageId = "msg_fixed",
        )
        e.ensureStarted()

        val line = launch { e.progress("holding") }
        opening.await()
        val ending = launch { e.emitTerminal(hasToolUse = false, incomplete = false, usage = Usage()) }
        testScheduler.runCurrent()
        assertTrue(frames.none { it.startsWith("event: message_delta") }, "the ending must wait: $frames")

        release.complete(Unit)
        line.join()
        ending.join()

        val delta = frames.indexOfFirst { it.contains("thinking_delta") }
        val stop = frames.indexOfFirst { it.startsWith("event: content_block_stop") }
        val end = frames.indexOfFirst { it.startsWith("event: message_delta") }
        assertTrue(delta >= 0 && delta < stop && stop < end, "line, then its close, then the ending: $frames")
    }
}
