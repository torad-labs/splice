package splice.core.perf.observation.aggregate

import splice.core.perf.observation.PerfScope

internal class PerfScopeBudget(private val memory: PerfMemoryAccount) {
    private var scopes = HashMap<String, MutableSet<PerfScope>>()
    private val overflow = PerfScope(head = "overflow", work = "unknown")
    var exceeded = 0L
        private set

    fun bound(metric: String, scope: PerfScope): PerfScope {
        val family = family(metric)
        val seen = scopes.getOrPut(family) { LinkedHashSet() }
        if (scope in seen) return scope
        if (seen.size >= PerfObservationLimits.MAX_METRIC_SCOPES - 1 || !memory.reserve(
                PerfMemoryBudget.OBSERVATION_SCOPE_BYTES,
            )
        ) {
            exceeded++
            return overflow
        }
        seen += scope
        return scope
    }

    fun clear() {
        memory.release(scopes.values.sumOf { it.size } * PerfMemoryBudget.OBSERVATION_SCOPE_BYTES)
        scopes = HashMap()
    }

    private fun family(metric: String): String = when {
        metric.startsWith("tool") -> "tools"
        metric.startsWith("attempt") -> "attempts"
        metric.startsWith("gate_") -> "gate"
        metric.startsWith("runtime_") -> "runtime"
        metric.startsWith("self_") -> "instrumentation"
        else -> "requests"
    }
}
