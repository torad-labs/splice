package splice.app.observability

import splice.app.sources.PerfSourceFiles
import splice.app.sources.observability.PerfAggregateStore
import splice.app.sources.observability.PerfEvidenceFileSource
import splice.core.perf.observation.PerfAccumulator
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfIssue
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.MonoClock
import splice.usage.perf.HeadAccountNames
import splice.usage.perf.RequestProjects
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.query.PerfQuerySources
import java.util.concurrent.ConcurrentHashMap

internal class PerfLiveSources(
    private val keys: Set<String>,
    private val rows: PerfSourceFiles,
    private val aggregate: PerfAggregateStore,
    private val accumulator: PerfAccumulator,
    private val evidence: PerfEvidenceFileSource,
    private val configuration: PerfConfigHistory,
) : PerfQuerySources {
    var enabled = true
    var runtimeSource: PerfRuntimeSource? = null
    var objectives: PerfObjectiveStore? = null
    var projects: RequestProjects? = null
    private val names = ConcurrentHashMap<String, Pair<Long, HeadAccountNames>>()

    fun accountNames(head: String, value: HeadAccountNames?) {
        if (value == null) names.remove(head) else names[head] = MonoClock.nowMs() to value
    }

    override fun heads(): Set<String> = keys
    override fun rows(head: String) = rows.rowsFor(head).takeIf { head in keys }
    override fun history() = aggregate
    override fun evidence() = evidence
    override fun provenance() = configuration
    override fun runtime() = runtimeSource
    override fun accountNames(
        head: String,
    ) = names[head]?.takeIf { MonoClock.nowMs() - it.first <= ACCOUNT_MAX_AGE_MS }?.second
    override fun objectiveSelection(id: String) = objectives?.selection(id)
    override fun objective(id: String) = objectives?.objective(id)
    override fun objectiveCohort(id: String) = objectives?.cohort(id)
    override fun live(): PerfAggregateSnapshot = accumulator.snapshot().let { snapshot ->
        if (enabled) {
            snapshot
        } else {
            snapshot.copy(
                coverage = snapshot.coverage.copy(
                    issues = snapshot.coverage.issues + PerfIssue("collection_disabled", "collection"),
                ),
            )
        }
    }
}

private const val ACCOUNT_MAX_AGE_MS = 60_000L
