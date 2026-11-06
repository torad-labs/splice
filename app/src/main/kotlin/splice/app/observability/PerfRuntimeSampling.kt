package splice.app.observability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.PerfWorkBudget
import splice.observability.runtime.RuntimeSampler
import splice.upstream.memory.JvmHeapRuntimeSource

/** Rebuilds only when actual resource owners change, preserving the sampler's bounded missing-owner diagnostics. */
internal class PerfRuntimeSampling(
    private val identity: PerfIdentity,
    private val recorder: PerfRecorder,
    private val owners: PerfRuntimeOwners,
    private val enabled: Boolean,
) : PerfRuntimeSource, AutoCloseable {
    private var selected = emptyList<PerfRuntimeSource>()
    private var sampler: RuntimeSampler? = null
    private val heap = JvmHeapRuntimeSource

    @Synchronized
    fun sample() {
        if (!enabled) return
        val next = listOf(heap) + owners.snapshot()
        if (selected != next) {
            sampler?.close()
            selected = next
            sampler = RuntimeSampler(identity, recorder, next)
        }
        sampler?.sample()?.let(PerfWorkBudget::release)
    }

    @Synchronized
    override fun runtime(): JsonObject {
        val current = sampler?.runtime() ?: return unavailable()
        val lease = PerfWorkBudget.acquire(REPLY_BYTES)
        return try {
            if (lease == null) return unavailable("work_budget_exhausted")
            val result = JsonObject(
                current + mapOf(
                    "collection_mode" to JsonPrimitive(if (enabled) "local" else "minimal"),
                    "owner_registration_overflow" to JsonPrimitive(owners.overflow),
                ),
            )
            lease.retain(result, REPLY_BYTES)
            result
        } finally {
            PerfWorkBudget.release(current)
        }
    }

    private fun unavailable(
        reason: String = if (enabled) "not_sampled" else "collection_disabled",
    ): JsonObject = JsonObject(
        mapOf(
            "collection_mode" to JsonPrimitive(if (enabled) "local" else "minimal"),
            "status" to JsonPrimitive("partial"),
            "reason" to JsonPrimitive(reason),
        ),
    )

    @Synchronized
    override fun close() { sampler?.close(); sampler = null }
}

// Reserve 96 KiB for each independent runtime reply until its caller releases the JSON tree.
private const val REPLY_BYTES = 96L * 1_024
