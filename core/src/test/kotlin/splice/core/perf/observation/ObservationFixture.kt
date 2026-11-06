package splice.core.perf.observation

import splice.core.util.ElapsedClock
import splice.core.util.WallClock

internal class ObservationFixture : AutoCloseable {
    val identity = PerfIdentity("boot-fixture", "fixture-build", "fixture-version", "revision-one")
    var now = 0L
    var wall = 0L
    val accumulator = PerfAccumulator(identity)
    val recorder = PerfRecorder(identity, accumulator, ElapsedClock { now }, WallClock { wall })
    val scope = PerfScope(head = "fixture")

    fun advance(ms: Long) {
        now += ms
        wall += ms
    }

    fun points(metric: String): List<PerfAggregatePoint> = accumulator.snapshot().points.filter { it.metric == metric }

    fun total(metric: String): Double = points(metric).sumOf { it.sum }

    override fun close() {
        accumulator.close()
    }
}
