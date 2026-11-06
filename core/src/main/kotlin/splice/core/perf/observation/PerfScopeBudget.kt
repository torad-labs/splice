package splice.core.perf.observation

internal class PerfScopeBudget {
    private val scopes = HashMap<String, MutableSet<PerfScope>>()
    private val overflow = PerfScope(head = "overflow", work = "unknown")
    var exceeded = 0L
        private set

    fun bound(metric: String, scope: PerfScope): PerfScope {
        val family = family(metric)
        val seen = scopes.getOrPut(family) { LinkedHashSet() }
        if (scope in seen) return scope
        if (seen.size >= PerfObservationLimits.MAX_SCOPES - 1) {
            exceeded++
            return overflow
        }
        seen += scope
        return scope
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
