package splice.app.observability

import splice.app.sources.PerfRowsFileSource
import splice.app.sources.observability.PerfAggregateStore
import splice.app.sources.observability.PerfEvidenceFileSource
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.perf.observation.PerfIdentity
import splice.core.util.WallClock
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.query.PerfQuerySources

/** Offline diagnostic constructors acquire no aggregate writer and never change its crash marker. */
internal class PerfOfflineSources(
    private val paths: StatePaths,
    private val keys: Set<String>,
) : PerfQuerySources, AutoCloseable {
    private val clock = WallClock(System::currentTimeMillis)
    private val aggregate =
        PerfAggregateStore(
            paths.perfAggregatesDir,
            PerfIdentity("offline", "unknown", GATEWAY_VERSION, "unknown"),
            clock,
        )
    private val evidence = PerfEvidenceFileSource(paths.perfEvidenceDir, clock)
    private val configuration = PerfConfigHistory(paths.perfConfigurationFile)
    private val objectives = PerfObjectiveStore(paths.perfObjectivesFile)
    private val rows = keys.associateWith { PerfRowsFileSource(paths.perfStatsFile(it), paths.perfArchiveDir) }

    override fun heads(): Set<String> = keys
    override fun rows(head: String) = rows[head]
    override fun history() = aggregate
    override fun evidence() = evidence
    override fun provenance() = configuration
    override fun objectiveSelection(id: String) = objectives.selection(id)
    override fun objective(id: String) = objectives.objective(id)
    override fun objectiveCohort(id: String) = objectives.cohort(id)

    override fun close() { aggregate.close(); configuration.close(); objectives.close() }
}
