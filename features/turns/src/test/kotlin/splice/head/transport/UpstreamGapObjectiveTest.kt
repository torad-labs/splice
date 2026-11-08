package splice.head.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import splice.core.perf.OutcomeTag
import splice.core.perf.TurnPerf
import splice.core.perf.UpstreamAttemptTiming
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.perf.observation.PerfObjectiveVerdict
import splice.core.perf.observation.PerfObjectives
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamGapObjectiveTest {
    @ParameterizedTest
    @CsvSource("10000,1000", "1000,10000")
    fun `request objectives include both event waits and the final upstream tail without duplicate gap samples`(
        beforeEvent: Long,
        afterEvent: Long,
    ) = runTest {
        val events = mutableListOf<PerfObservation>()
        val clock = ElapsedClock { testScheduler.currentTime }
        val recorder = PerfRecorder(
            PerfIdentity("gap-objective", "test", "1", "config"),
            PerfObserver { events.add(it) },
            clock,
            WallClock { 60_000L + clock() },
        )
        val request = recorder.request(PerfScope(head = "test"), 0L, 60_000L)
        val perf = TurnPerf(observations = request, clock = clock)
        val attempt = UpstreamAttemptTiming(perf)
        val text = Json.parseToJsonElement(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"synthetic"}}""",
        ).jsonObject
        UpstreamEventTiming(perf, 0).observe(
            flow {
                delay(beforeEvent)
                emit(text)
                delay(afterEvent)
            },
        ).toList()
        attempt.finish(OutcomeTag.OK.wire)
        request.finish(OutcomeTag.OK.wire)
        val terminal = events.single { it.kind == PerfEventKind.REQUEST_FINISHED }
        assertEquals(10_000.0, terminal.values["upstream_gap_max_ms"])
        assertEquals(
            PerfObjectiveVerdict.BAD,
            PerfObjectives.evaluate(
                terminal.outcome,
                terminal.values,
                PerfObjectiveSet("progress", "1", progressGapMs = 5_000.0),
            ),
        )
        val gaps = events.filter { it.kind == PerfEventKind.GAP && "upstream_gap_ms" in it.values }
        assertEquals(2, gaps.size)
        assertEquals(11_000.0, gaps.sumOf { it.values.getValue("upstream_gap_ms") })
    }
}
