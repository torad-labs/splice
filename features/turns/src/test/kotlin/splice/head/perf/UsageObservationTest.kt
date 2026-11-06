package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.TurnBill
import splice.core.perf.OutcomeTag
import splice.core.perf.TurnPerf
import splice.core.perf.observation.PerfAccumulator
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.turn.AbsorbedRounds
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory
import splice.core.turn.UsageOrigin
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

class UsageObservationTest {
    @Test
    fun `late canonical usage belongs to the original client completion and never closes the request twice`() {
        var now = 0L
        val clock = ElapsedClock { now }
        val identity = PerfIdentity("usage", "test", "test", "test")
        val aggregate = PerfAccumulator(identity)
        val recorder = PerfRecorder(identity, aggregate, clock, WallClock { 60_000L + now })
        val request = recorder.request(PerfScope(head = "test"), 0L, 60_000L)
        val perf = TurnPerf(observations = request, clock = clock)
        now = 10
        request.finish(OutcomeTag.CLIENT_ABORT.wire)
        now = 70_000
        val usage = Usage(
            inputTokens = 100,
            outputTokens = 40,
            cachedTokens = 20,
            origin = UsageOrigin(history = UsageHistory(absorbed = AbsorbedRounds(1, 50, 10, 0, 15))),
            reported = UsageField.entries.toSet(),
        )
        TurnBill.counters(usage).forEach { (key, value) -> perf.setCount(key, value) }
        UsageObservation.record(perf, perf.snapshot())
        UsageObservation.record(perf, perf.snapshot())
        val points = aggregate.snapshot().points
        assertEquals(1.0, points.filter { it.metric == "requests_finished" }.sumOf { it.sum })
        assertEquals(150.0, points.filter { it.metric == "input_tokens" }.sumOf { it.sum })
        assertEquals(40.0, points.filter { it.metric == "output_tokens" }.sumOf { it.sum })
        assertEquals(30.0, points.filter { it.metric == "cached_tokens" }.sumOf { it.sum })
        assertTrue(points.filter { it.metric.endsWith("_tokens") }.all { it.fromMs == 60_000L })
    }
}
