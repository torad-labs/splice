package splice.head.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.TurnBill
import splice.core.perf.OutcomeTag
import splice.core.perf.TurnPerf
import splice.core.perf.UsageMeasurementStamp
import splice.core.perf.observation.PerfAccumulator
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.turn.AbsorbedRounds
import splice.core.turn.CacheWrite
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageHistory
import splice.core.turn.UsageMeasure
import splice.core.turn.UsageOrigin
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

class UsageObservationTest {
    @Test
    fun `unreported cache write remains absent while observed zero and hourly subset remain facts`() {
        val missing = canonical(Usage(inputTokens = 10, reported = setOf(UsageField.INPUT)))
        assertTrue("usage_cache_write_tokens" !in missing.values)
        assertTrue("usage_cache_write_1h_tokens" !in missing.values)
        val zero = canonical(Usage(inputTokens = 10, origin = UsageOrigin(measured = UsageMeasure.entries.toSet())))
        assertEquals(0.0, zero.values["usage_cache_write_tokens"])
        assertEquals(0.0, zero.values["usage_cache_write_1h_tokens"])
        val written =
            canonical(
                Usage(
                    inputTokens = 10,
                    cacheWrite = CacheWrite(5, 2),
                    origin = UsageOrigin(measured = UsageMeasure.entries.toSet()),
                ),
            )
        assertEquals(5.0, written.values["usage_cache_write_tokens"])
        assertEquals(2.0, written.values["usage_cache_write_1h_tokens"])
    }

    @Test
    fun `canonical local ownership absorbed counts and cut markers survive without splitting round sizes`() {
        val local = canonical(Usage(origin = UsageOrigin(localStep = true)))
        assertEquals(1.0, local.values["usage_no_request"])
        val folded = canonical(
            Usage(
                inputTokens = 10,
                cacheWrite = CacheWrite(2, 1),
                origin = UsageOrigin(
                    history = UsageHistory(absorbed = AbsorbedRounds(2, 40, 5, 10, 20), cutRounds = 1),
                ),
            ),
        )
        assertTrue("usage_cache_write_tokens" !in folded.values, "cut rounds cannot prove the complete token total")
        assertEquals(2.0, folded.values["usage_absorbed_rounds"])
        assertEquals(1.0, folded.values["usage_cut_rounds"])
        assertEquals(1.0, folded.values["missing_usage"])
    }

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

    private fun canonical(usage: Usage): PerfObservation {
        val events = ArrayList<PerfObservation>()
        val clock = ElapsedClock { 0L }
        val recorder =
            PerfRecorder(
                PerfIdentity("usage", "test", "test", "test"),
                PerfObserver { events.add(it) },
                clock,
                WallClock { 100L },
            )
        val request = recorder.request(PerfScope(head = "test"), 0, 100)
        val perf = TurnPerf(observations = request, clock = clock)
        request.finish(OutcomeTag.OK.wire)
        TurnBill.counters(usage).forEach { (key, value) -> perf.setCount(key, value) }
        UsageMeasurementStamp.counters(usage).forEach { (key, value) -> perf.setCount(key, value) }
        UsageObservation.record(perf, perf.snapshot())
        return events.single { it.kind == PerfEventKind.USAGE_RECORDED }
    }
}
