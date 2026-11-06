package splice.app.observability

import splice.app.sources.PerfSourceFiles
import splice.app.sources.observability.PerfAggregateStore
import splice.app.sources.observability.PerfEvidenceFileSource
import splice.core.config.StatePaths
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfAggregateSource
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.aggregate.PerfAccumulator
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.head.perf.RequestEvidenceFiles
import splice.usage.perf.history.PerfEvidenceSinkSource

/** Owns local observation storage; persistence drains before readers close, and the accumulator closes last. */
internal class PerfCollection(
    private val paths: StatePaths,
    private val identity: PerfIdentity,
    private val heads: Set<String>,
    private val rows: PerfSourceFiles,
    private val enabled: Boolean,
    clock: WallClock,
    elapsed: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) : PerfAggregateSource, AutoCloseable {
    private val accumulator = PerfAccumulator(identity)
    private val aggregate = PerfAggregateStore(paths.perfAggregatesDir, identity, clock)
    private val evidenceFiles = RequestEvidenceFiles(paths.perfEvidenceDir, rows.kept, clock, elapsed = elapsed)
    private val evidenceStores = heads.associateWith(evidenceFiles::forHead)
    private val evidence = PerfEvidenceFileSource(
        paths.perfEvidenceDir,
        clock,
        PerfEvidenceSinkSource { evidenceStores.values.map { it.sink.status() } },
    )

    fun fanout(exports: PerfObserver): PerfFanout = PerfFanout(
        listOf(
            PerfFanoutTarget(accumulator),
            PerfFanoutTarget(exports),
            PerfFanoutTarget(
                PerfObserver { event -> evidenceStores[event.scope.head]?.record(event) ?: true },
                PerfLoss { head -> evidenceFiles.faultCoverage(head.takeIf { it in heads }) },
            ),
        ),
    )

    override fun snapshot(): PerfAggregateSnapshot = accumulator.snapshot().let { snapshot ->
        if (enabled) {
            snapshot
        } else {
            snapshot.copy(
                coverage = snapshot.coverage.copy(
                    observedMs = 0,
                    issues = snapshot.coverage.issues + PerfIssue("collection_disabled", "collection"),
                ),
            )
        }
    }

    fun sources(configuration: PerfConfigHistory): PerfLiveSources =
        PerfLiveSources(heads, rows, aggregate, accumulator, evidence, configuration)

    fun start() {
        if (enabled) aggregate.openWriter()
        if (enabled) heads.forEach { evidenceFiles.startCoverage(it, identity.bootId) }
    }

    fun checkpoint() {
        if (aggregate.status().pending > 0) return
        val lease = PerfWorkBudget.acquire(TELEMETRY_SNAPSHOT_BYTES) ?: return
        // Accumulator snapshots detach points, histograms and coverage lists; the writer can take this sole copy.
        lease.use { aggregate.appendOwned(accumulator.snapshot(), it) }
    }

    fun checkpointCoverage() { evidenceFiles.checkpointCoverage() }

    fun diagnostics(
        configuration: PerfConfigHistory,
        observer: PerfFanout,
        cleanup: PerfCleanup,
    ): PerfStatusSnapshot {
        val sinks = listOf(
            accumulator.status(),
            aggregate.status(),
            evidenceFiles.coverageSink.status(),
            configuration.writes.status(),
        ) + evidenceStores.values.take(MAX_DIAGNOSTIC_HEADS).map { it.sink.status() }
        val issues = buildList {
            if (!enabled) add("collection_disabled")
            if (observer.failures.get() > 0) add("observer_failure")
            if (observer.dropped.get() > 0) add("observer_drop")
            if (evidenceStores.size > MAX_DIAGNOSTIC_HEADS) add("sink_status_truncated")
            if (sinks.any { it.failed > 0 || it.dropped > 0 }) add("sink_loss")
            addAll(cleanup.issues())
        }
        return PerfStatusSnapshot(sinks, aggregate.costs.snapshot(), evidence.costs.snapshot(), issues)
    }

    fun closePersistence(cleanup: PerfCleanup) = cleanup.finish(
        { cleanup.run("evidence_stop") { if (enabled) heads.forEach { evidenceFiles.stopCoverage(it) } } },
        {
            cleanup.run("evidence_flush") {
                if (!AsyncFileIo.awaitDirectory(paths.perfEvidenceDir)) cleanup.failed("evidence_unsettled")
            }
        },
        { cleanup.run("evidence") { evidenceFiles.close() } },
        { cleanup.run("aggregate") { aggregate.close() } },
    )

    /** Readers/exporters have already drained; only now can their source reservation be released. */
    override fun close() { accumulator.close() }
}

// Reserve 2 MiB for checkpoint snapshot work so contention skips the checkpoint instead of borrowing unbounded memory.
private const val TELEMETRY_SNAPSHOT_BYTES = 2L * 1_024 * 1_024

// Report at most sixty head sink statuses and mark truncation to bound the status projection.
private const val MAX_DIAGNOSTIC_HEADS = 60
