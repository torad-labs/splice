// The scenarios that go quiet, or are held open by the test: the watchdog tiers, the hold latches, the slow prefill.
package splice.head

import java.util.concurrent.CountDownLatch

private const val IDLE_SLEEP_MS = 5_000L
private const val PREFILL_SILENCE_MS = 1_500L
private const val DRIP_GAP_MS = 40L

/** The two latches a test releases on command. SEPARATE on purpose: "holdstart" blocks BEFORE any event while "hold"
 *  blocks AFTER its first delta, and sharing one latch let whichever test armed it last release the other's upstream
 *  mid-assertion (observed as a cross-test failure in the stop-drain case). */
internal class MockHoldLatches {
    @Volatile var hold = CountDownLatch(1)

    @Volatile var start = CountDownLatch(1)
}

internal class MockStallScenarios(private val wire: MockSseWire, private val latches: MockHoldLatches) {
    private val e = wire.events

    /** Plays [scenario] when it is a quiet or held one, and says whether it was. */
    fun play(scenario: String): Boolean {
        when (scenario) {
            "idlepre" -> acknowledgeThenSilence()
            "idle" -> partialThenSilence()
            "holdstart" -> heldBeforeFirstItem()
            "hold" -> heldAfterFirstDelta()
            "prefill" -> slowPrefill()
            "drip" -> drip()
            else -> return false
        }
        return true
    }

    // DR-7: an acknowledgement, then silence — the PRE-CONTENT stall. "Pre-content" means no CLIENT FRAME has been
    // emitted (the state G5's reissue path claims); it does NOT mean no event and not no byte. The distinction
    // matters because response.created IS bytes on the wire but NOT a client frame, so the watchdog keeps its
    // first-output tier (firstByteTimeout) through the stall — bytes touch the slot, frames pick the tier
    // (Watchdog.kt, 2026-09-01). See the arm in SseRoundConsumeTest, whose budgets are split to prove which tier
    // fires. A scenario that only slept would be untestable, because the client blocks on headers and the stall
    // would land before the head had a stream to watch at all; and a bare SSE comment ends the round instantly as a
    // dead-head body rather than stalling it.
    private fun acknowledgeThenSilence() {
        wire.sse("""{"type":"response.created","response":{"id":"rs_idle"}}""")
        wire.pause(STALL_SLEEP_MS)
    }

    private fun partialThenSilence() {
        wire.sse(e.messageAdded())
        wire.sse(e.textDelta("partial"))
        wire.pause(IDLE_SLEEP_MS)
    }

    // Models a silent response before its first model item. The head stages its structural opener during the
    // bounded HTTP-status hold, then opens and flushes the response when the latch releases an actual content item.
    private fun heldBeforeFirstItem() {
        latches.start.await()
        wire.sse(e.messageAdded())
        wire.sse(e.textDelta("late"))
        wire.sse(e.itemDone())
        wire.sse(e.completed("rhs", input = 1, output = 1))
    }

    private fun heldAfterFirstDelta() {
        wire.sse(e.messageAdded())
        wire.sse(e.textDelta("held"))
        latches.hold.await() // block until the test releases, then finish cleanly
        wire.sse(e.itemDone())
        wire.sse(e.completed("rhold", input = 1, output = 1))
    }

    private fun slowPrefill() {
        wire.pause(PREFILL_SILENCE_MS) // silent past streamIdle — governed by firstByteTimeout
        wire.sse(e.messageAdded())
        wire.sse(e.textDelta("summary after slow prefill"))
        wire.sse(e.itemDone())
        wire.sse("""{"type":"response.completed","response":{"usage":{"input_tokens":1000,"output_tokens":5}}}""")
    }

    private fun drip() {
        wire.sse(e.messageAdded())
        while (true) {
            wire.sse(e.textDelta("drip "))
            wire.pause(DRIP_GAP_MS)
        }
    }
}
