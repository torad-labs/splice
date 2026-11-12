package splice.observability.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import java.util.IdentityHashMap

/** App schedules sample every five seconds. Reads use the cached sample and never emit observations. */
public class RuntimeSampler(
    identity: PerfIdentity,
    private val recorder: PerfRecorder,
    sources: List<PerfRuntimeSource> = emptyList(),
    private val wallClock: WallClock = WallClock(System::currentTimeMillis),
    private val elapsedClock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) : PerfRuntimeSource, AutoCloseable {
    private val owners = RuntimeOwners(sources)
    private val counters = JvmRuntimeCounters()
    private val payloads = RuntimePayloads(identity)
    private val memory = PerfWorkBudget.acquire(RUNTIME_SAMPLE_BYTES, PerfWorkBudget.Kind.RUNTIME_SAMPLE)
    private var closed = false
    private var sampledAt = 0L
    private var sampledMono = 0L
    private var latest: JsonObject = payloads.unavailable("not_sampled")

    @Synchronized
    public fun sample(): JsonObject {
        if (closed || memory == null) return payloads.unavailable(if (closed) "closed" else "work_budget_exhausted")
        val lease = PerfWorkBudget.acquire(RUNTIME_SAMPLE_BYTES, PerfWorkBudget.Kind.RUNTIME_SAMPLE)
            ?: return payloads.unavailable("work_budget_exhausted")
        var transferred = false
        try {
            val reading = RuntimeReading()
            val now = elapsedClock()
            counters.sample(now, reading)
            val sampledWall = wallClock()
            val ownerReadings = ownerReadings(reading, sampledWall)
            RuntimeUnknowns.fill(reading)
            sampledAt = sampledWall
            sampledMono = now
            latest = payloads.sample(reading, ownerReadings, owners.omitted, sampledAt)
            recorder.emit(
                PerfScope(work = "runtime"),
                PerfEventKind.RUNTIME,
                reading.values.mapNotNull { (key, value) ->
                    value?.let { key to it }
                }.toMap(),
            )
            // Transfer the lease to the immutable value, including readers retaining an older sample.
            lease.retain(latest, RUNTIME_SAMPLE_BYTES)
            transferred = true
            return latest
        } finally {
            if (!transferred) lease.close()
        }
    }

    @Synchronized
    override fun runtime(): JsonObject {
        if (sampledAt == 0L || closed) return latest
        val lease = PerfWorkBudget.acquire(RUNTIME_SAMPLE_BYTES, PerfWorkBudget.Kind.RUNTIME_SAMPLE)
            ?: return payloads.unavailable("work_budget_exhausted")
        return payloads.aged(latest, elapsedClock() - sampledMono).also { lease.retain(it, RUNTIME_SAMPLE_BYTES) }
    }

    @Synchronized
    override fun close() {
        closed = true
        latest = payloads.unavailable("closed")
        memory?.close()
    }

    private fun ownerReadings(reading: RuntimeReading, nowMs: Long) = buildJsonArray {
        val previous = latest["owner_readings"] as? JsonArray
        owners.sources.forEachIndexed { index, source ->
            add(RuntimeOwnerSampling.sample(source, index, previous?.getOrNull(index)?.jsonObject, reading, nowMs))
        }
    }
}

// Reserve 64 KiB for each cached sample or independent reader copy, releasing it with that owner.
private const val RUNTIME_SAMPLE_BYTES = 64L * 1_024

// Admit at most eight distinct runtime owners; report omitted sources instead of growing the sampling registry.
private const val MAX_RUNTIME_SOURCES = 8

/** Owners must be actual process resources, not a fresh adapter for each head sharing one resource. */
private class RuntimeOwners(input: List<PerfRuntimeSource>) {
    val sources = mutableListOf<PerfRuntimeSource>()
    var omitted = 0
        private set

    init {
        val seen = IdentityHashMap<PerfRuntimeSource, Boolean>()
        input.forEach { source ->
            if (!seen.containsKey(source)) {
                if (sources.size < MAX_RUNTIME_SOURCES) {
                    seen[source] = true
                    sources.add(source)
                } else {
                    omitted++
                }
            }
        }
    }
}
