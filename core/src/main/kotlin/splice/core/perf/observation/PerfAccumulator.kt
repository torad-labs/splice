package splice.core.perf.observation

/** Locked bounded minute aggregates. Record and snapshot do no I/O and allocate no coroutine work. */
public class PerfAccumulator(private val identity: PerfIdentity) : PerfObserver, PerfAggregateSource {
    private val lock = Any()
    private val timeline = PerfTimeline()
    private val cells = PerfCells()
    private val gauges = PerfGaugeBook(cells)
    private val scopes = PerfScopeBudget()
    private val lifecycle = PerfLifecycle(cells, gauges, scopes)
    private val seen = PerfRecentIds()
    private var observed = 0L
    private var accepted = 0L
    private var invalid = 0L
    private var mismatched = 0L

    override fun record(observation: PerfObservation): Boolean = synchronized(lock) {
        observed++
        when {
            observation.stamp.identity != identity -> { mismatched++; false }
            PerfDimensions.id(observation.stamp.observationId) == null -> { invalid++; false }
            !seen.add(observation.stamp.observationId) -> false
            else -> accumulate(observation)
        }
    }

    override fun snapshot(): PerfAggregateSnapshot = synchronized(lock) {
        val copy = cells.copy()
        gauges.preview(copy, timeline.last)
        val until = if (accepted == 0L) 0L else timeline.last + 1
        val points = copy.points(until)
        val from = points.minOfOrNull { it.fromMs } ?: timeline.first
        PerfAggregateSnapshot(
            identity,
            from,
            until,
            points,
            PerfCoverage(
                from,
                until,
                minOf(timeline.observed, (timeline.last - from).coerceAtLeast(0)),
                issues(copy),
                listOf(status()),
            ),
        )
    }

    private fun accumulate(observation: PerfObservation): Boolean {
        val safe = observation.copy(
            scope = PerfDimensions.scope(observation.scope),
            links = PerfDimensions.links(observation.links),
        )
        val at = timeline.advance(safe.stamp)
        cells.expire(at)
        if (!lifecycle.record(safe, at)) return false
        accepted++
        var valid = true
        safe.values.forEach { (metric, value) ->
            if (!PerfMetricRegistry.accepts(metric, safe.kind, value)) {
                invalid++
                valid = false
            } else {
                recordValue(safe, metric, value, at)
            }
        }
        return valid
    }

    private fun recordValue(event: PerfObservation, metric: String, value: Double, at: Long) {
        // First-content values travel in live observations; their distribution is recorded only at request terminal.
        if (event.kind == PerfEventKind.CONTENT_PROGRESS && metric.startsWith("arrival_to_first_")) return
        if (event.kind == PerfEventKind.REQUEST_ADMITTED && metric == "queue_wait_ms") return
        val scope = scopes.bound(metric, valueScope(event))
        if (PerfMetricRegistry.definition(metric)?.type == PerfMetricType.GAUGE) {
            gauges.set(metric, scope, value, at)
        } else {
            cells.add(metric, scope, at, value)
        }
    }

    private fun valueScope(event: PerfObservation): PerfScope = when (event.kind) {
        PerfEventKind.REQUEST_FINISHED, PerfEventKind.ATTEMPT_FINISHED, PerfEventKind.TOOL_FINISHED ->
            event.scope.copy(outcome = event.outcome?.let(PerfDimensions::outcome) ?: "unknown")
        PerfEventKind.REQUEST_ARRIVED, PerfEventKind.REQUEST_ADMITTED, PerfEventKind.ATTEMPT_STARTED,
        PerfEventKind.PROTOCOL_PROGRESS, PerfEventKind.CONTENT_PROGRESS, PerfEventKind.CLIENT_PROGRESS,
        PerfEventKind.GAP, PerfEventKind.GATE_STATE, PerfEventKind.TOOL_STARTED, PerfEventKind.TOOL_PHASE,
        PerfEventKind.RUNTIME, PerfEventKind.SELF_COST, PerfEventKind.CHECKPOINT,
        -> event.scope.copy(outcome = null)
    }

    private fun issues(view: PerfCells): List<PerfIssue> {
        val counts = linkedMapOf(
            "invalid_observation" to invalid,
            "identity_mismatch" to mismatched,
            "aggregate_overflow" to view.dropped,
            "cardinality_overflow" to scopes.exceeded,
            "gauge_overflow" to gauges.dropped,
            "active_handle_overflow" to lifecycle.dropped,
            "terminal_without_start" to lifecycle.orphanTerminals,
            "clock_discontinuity" to timeline.clockChanges,
        )
        val issues = counts.filterValues { it > 0 }.map { (code, count) -> PerfIssue(code, ACCUMULATOR, count) }
        val retention = view.expiredBefore?.let {
            listOf(PerfIssue("retention_expired", ACCUMULATOR, detail = "Intervals before $it expired"))
        } ?: emptyList()
        return issues + retention + if (accepted == 0L) {
            listOf(
                PerfIssue("no_observations", ACCUMULATOR),
            )
        } else {
            emptyList()
        }
    }

    private fun status(): PerfSinkStatus = PerfSinkStatus(
        ACCUMULATOR, observed, accepted, accepted, mismatched, observed - accepted - mismatched,
        0, null, if (accepted > 0) timeline.last else null,
    )
}

private const val ACCUMULATOR = "accumulator"
