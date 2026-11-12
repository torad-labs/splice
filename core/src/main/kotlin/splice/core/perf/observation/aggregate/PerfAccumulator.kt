package splice.core.perf.observation.aggregate

import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfDimensions
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfMetricRegistry
import splice.core.perf.observation.PerfMetricType
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfProvenance
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfSinkStatus

/** Locked bounded minute aggregates. Record and snapshot do no I/O and allocate no coroutine work. */
public class PerfAccumulator(identity: PerfIdentity) : PerfObserver, PerfAggregateSource, AutoCloseable {
    private val identity = PerfProvenance.bounded(identity)
    private val lock = Any()
    private val memory = PerfMemoryAccount()
    private var closed = false
    private val timeline = PerfTimeline()
    private val cells = PerfCells(memory)
    private val gauges = PerfGaugeBook(cells, memory)
    private val scopes = PerfScopeBudget(memory)
    private val lifecycle = PerfLifecycle(cells, gauges, scopes, memory)
    private val gaugePreview = PerfGaugePreview()
    private val checkpointCoverage = object : PerfCheckpointCoverage {
        override fun measure(view: PerfCells, from: Long, until: Long): PerfCoverage {
            val observedFrom = maxOf(from, timeline.first)
            val observedUntil = minOf(until, timeline.last + 1)
            return PerfCoverage(
                from,
                until,
                minOf(timeline.observed, (observedUntil - observedFrom).coerceAtLeast(0L)),
                issues(view),
                listOf(status()),
            )
        }
    }
    private val checkpointPages = PerfCheckpointPages(identity, cells, gauges, timeline, checkpointCoverage)
    private val seen = PerfRecentIds(memory)
    private val usageSeen = PerfRecentIds(memory)
    private var observed = 0L
    private var accepted = 0L
    private var invalid = 0L
    private var mismatched = 0L
    private var recordingDrops = 0L

    override fun record(observation: PerfObservation): Boolean = synchronized(lock) {
        observed++
        when {
            closed -> false
            observation.stamp.identity != identity -> { mismatched++; false }
            PerfDimensions.id(observation.stamp.observationId) == null -> { invalid++; false }
            else -> {
                val reserved = lifecycle.terminalReservation(observation)
                if (seen.add(observation.stamp.observationId, reserved)) accumulate(observation, reserved) else false
            }
        }
    }

    override fun snapshot(): PerfAggregateSnapshot = synchronized(lock) {
        if (closed) return unavailableSnapshot("collector_closed")
        if (!PerfMemoryBudget.beginSnapshot()) return unavailableSnapshot("snapshot_busy")
        try {
            snapshotState()
        } finally {
            PerfMemoryBudget.endSnapshot()
        }
    }

    /** Detaches one fair, bounded dirty-minute page for durable checkpointing. */
    public fun checkpointPage(): PerfAggregateSnapshot? = synchronized(lock) {
        if (closed) return null
        if (accepted > 0L) gaugePreview.advance(cells, gauges, accepted, timeline.last)
        checkpointPages.capture(accepted > 0L)
    }

    /** A dirty minute is cleared only after its exact page generation is durably written. */
    public fun settleCheckpointPage(page: PerfAggregateSnapshot, durable: Boolean): Unit = synchronized(lock) {
        checkpointPages.settle(page, durable)
    }

    override fun close(): Unit = synchronized(lock) {
        if (!closed) {
            closed = true
            cells.clear()
            checkpointPages.clear()
            gauges.clear()
            scopes.clear()
            lifecycle.clear()
            seen.clear()
            usageSeen.clear()
            memory.close()
        }
    }

    private fun snapshotState(): PerfAggregateSnapshot {
        val copy = cells.copy()
        gauges.preview(copy, timeline.last)
        val until = if (accepted == 0L) 0L else timeline.last + 1
        val points = copy.points(until)
        val from = points.minOfOrNull { it.fromMs } ?: timeline.first
        return PerfAggregateSnapshot(
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

    private fun unavailableSnapshot(code: String): PerfAggregateSnapshot = PerfAggregateSnapshot(
        identity,
        timeline.first,
        timeline.last,
        emptyList(),
        PerfCoverage(timeline.first, timeline.last, 0, listOf(PerfIssue(code, ACCUMULATOR)), listOf(status())),
    )

    private fun accumulate(observation: PerfObservation, terminalReservation: Long): Boolean {
        val safe = observation.copy(
            scope = PerfDimensions.scope(observation.scope),
            links = PerfDimensions.links(observation.links),
        )
        val at = if (safe.kind == PerfEventKind.USAGE_RECORDED) {
            timeline.attributed(safe.stamp)
        } else {
            timeline.advance(safe.stamp)
        }
        cells.expire(timeline.last)
        gaugePreview.advance(cells, gauges, accepted, timeline.last)
        if (!lifecycle.record(safe, at, terminalReservation)) return false
        accepted++
        val recordUsage = acceptUsage(safe)
        return accumulateValues(safe, at, recordUsage)
    }

    private fun accumulateValues(safe: PerfObservation, at: Long, recordUsage: Boolean): Boolean {
        var valid = true
        safe.values.forEach { (metric, value) ->
            if (!PerfMetricRegistry.accepts(metric, safe.kind, value)) {
                invalid++
                valid = false
            } else {
                if (recordUsage || !PerfMetricRegistry.isUsageMetric(metric)) {
                    valid = recordValue(safe, metric, value, at) && valid
                }
            }
        }
        return valid
    }

    private fun acceptUsage(event: PerfObservation): Boolean {
        val hasUsage = event.values.any { (metric, value) ->
            PerfMetricRegistry.isUsageMetric(metric) && PerfMetricRegistry.accepts(metric, event.kind, value)
        }
        return !hasUsage || usageSeen.add(event.links.requestId ?: event.stamp.observationId)
    }

    private fun recordValue(event: PerfObservation, metric: String, value: Double, at: Long): Boolean {
        if (metric == "self_dropped") recordingDrops += value.toLong()
        // First-content values travel in live observations; their distribution is recorded only at request terminal.
        if (PerfMetricRegistry.isBoundaryTiming(metric) && event.kind != PerfEventKind.REQUEST_FINISHED) return true
        if (event.kind == PerfEventKind.REQUEST_ADMITTED && metric == "queue_wait_ms") return true
        val scope = scopes.bound(metric, valueScope(event))
        return if (PerfMetricRegistry.definition(metric)?.type == PerfMetricType.GAUGE) {
            val recorded = gauges.set(metric, scope, value, at)
            if (at < timeline.last) gaugePreview.markLate(cells, at, timeline.last)
            recorded
        } else {
            cells.add(metric, scope, at, value)
        }
    }

    private fun valueScope(event: PerfObservation): PerfScope = when (event.kind) {
        PerfEventKind.REQUEST_FINISHED, PerfEventKind.ATTEMPT_FINISHED, PerfEventKind.TOOL_FINISHED,
        PerfEventKind.USAGE_RECORDED,
        ->
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
            "late_observation" to timeline.lateObservations,
            "late_observation_expired" to view.expiredInputs,
            "checkpoint_page_expired" to view.expiredDirtyPages,
            "checkpoint_page_empty" to view.emptyPages,
            "memory_budget_exhausted" to memory.rejected,
            "observation_dropped" to recordingDrops,
        )
        val issues = counts.filterValues { it > 0 }.map { (code, count) -> PerfIssue(code, ACCUMULATOR, count) }
        val retention = view.expiredBefore?.let {
            listOf(
                PerfIssue(
                    "retention_expired",
                    ACCUMULATOR,
                    detail = "Intervals before $it expired",
                    fromMs = Long.MIN_VALUE,
                    untilMs = it,
                ),
            )
        } ?: emptyList()
        return issues + retention + if (accepted == 0L) {
            listOf(
                PerfIssue("no_observations", ACCUMULATOR),
            )
        } else {
            emptyList()
        }
    }

    public fun status(): PerfSinkStatus = synchronized(lock) {
        PerfSinkStatus(
            ACCUMULATOR, observed, accepted, accepted, mismatched, observed - accepted - mismatched,
            0, null, if (accepted > 0) timeline.last else null,
        )
    }

    /** Number of retained dirty pages still awaiting a durable checkpoint. */
    public val pendingCheckpointPages: Int
        get() = synchronized(lock) { cells.dirtyPages.count() }
}

private const val ACCUMULATOR = "accumulator"
