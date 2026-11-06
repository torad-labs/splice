package splice.core.perf.observation

/** Separate request/attempt/tool handles; identifiers never become a metric key or dimension. */
internal class PerfLifecycle(
    private val cells: PerfCells,
    private val gauges: PerfGaugeBook,
    private val scopes: PerfScopeBudget,
) {
    private val active = HashMap<String, PerfScope>()
    private val terminals = PerfRecentIds()
    var dropped = 0L
        private set
    var orphanTerminals = 0L
        private set

    fun record(event: PerfObservation, at: Long): Boolean = when (event.kind) {
        PerfEventKind.REQUEST_ARRIVED -> start(REQUESTS, event.links.requestId, event.scope, at)
        PerfEventKind.ATTEMPT_STARTED -> start(ATTEMPTS, event.links.attemptId, event.scope, at)
        PerfEventKind.TOOL_STARTED -> start("tools", event.links.toolId, event.scope, at)
        PerfEventKind.REQUEST_FINISHED -> finish(REQUESTS, event.links.requestId, event, at)
        PerfEventKind.ATTEMPT_FINISHED -> finish(ATTEMPTS, event.links.attemptId, event, at)
        PerfEventKind.TOOL_FINISHED -> finish("tools", event.links.toolId, event, at)
        PerfEventKind.REQUEST_ADMITTED, PerfEventKind.PROTOCOL_PROGRESS, PerfEventKind.CONTENT_PROGRESS,
        PerfEventKind.CLIENT_PROGRESS, PerfEventKind.GAP, PerfEventKind.GATE_STATE, PerfEventKind.TOOL_PHASE,
        PerfEventKind.RUNTIME, PerfEventKind.SELF_COST, PerfEventKind.CHECKPOINT,
        -> transition(event, at)
    }

    private fun transition(event: PerfObservation, at: Long): Boolean {
        if (event.kind == PerfEventKind.CHECKPOINT || event.kind == PerfEventKind.REQUEST_ADMITTED) {
            refine(REQUESTS, event.links.requestId, event.scope, at)
            refine(ATTEMPTS, event.links.attemptId, event.scope, at)
            refine("tools", event.links.toolId, event.scope, at)
        }
        if (event.kind == PerfEventKind.REQUEST_ADMITTED) count("requests_admitted", event.scope, at)
        return true
    }

    private fun start(family: String, id: String?, scope: PerfScope, at: Long): Boolean {
        val key = id?.let { "$family:$it" }
        if (key?.let(::alreadyKnown) == true) return false
        val live = scopes.bound(liveMetric(family), scope.copy(outcome = null))
        count(if (family == REQUESTS) "requests_arrived" else "${family}_started", live, at)
        if (key == null || active.size >= PerfObservationLimits.MAX_HANDLES) {
            dropped++
        } else {
            active[key] = live
            gauges.change(liveMetric(family), live, 1.0, at)
        }
        return true
    }

    private fun liveMetric(family: String): String = "${family}_live"

    private fun alreadyKnown(key: String): Boolean = key in active || terminals.contains(key)

    private fun finish(family: String, id: String?, event: PerfObservation, at: Long): Boolean {
        val key = id?.let { "$family:$it" }
        if (key != null && !terminals.add(key)) return false
        val previous = key?.let(active::remove)
        if (previous != null) gauges.change(liveMetric(family), previous, -1.0, at) else orphanTerminals++
        val scope = event.scope.copy(outcome = event.outcome?.let(PerfDimensions::outcome) ?: "unknown")
        count("${family}_finished", scope, at)
        if (family == REQUESTS) count(requestOutcome(scope.outcome), scope, at)
        return true
    }

    private fun refine(family: String, id: String?, scope: PerfScope, at: Long) {
        val key = id?.let { "$family:$it" } ?: return
        val old = active[key] ?: return
        val replacement = scopes.bound(liveMetric(family), scope.copy(outcome = null))
        if (old != replacement) {
            gauges.change(liveMetric(family), old, -1.0, at)
            gauges.change(liveMetric(family), replacement, 1.0, at)
            active[key] = replacement
        }
    }

    private fun count(metric: String, scope: PerfScope, at: Long) {
        cells.add(metric, scopes.bound(metric, scope), at, 1.0)
    }

    private fun requestOutcome(outcome: String?): String = when (outcome) {
        "good" -> "requests_good"
        "unknown", null -> "requests_unknown"
        else -> "requests_bad"
    }
}

private const val REQUESTS = "requests"

private const val ATTEMPTS = "attempts"
