package splice.app.observability

import splice.core.config.StatePaths
import splice.core.perf.observation.PerfIdentity
import splice.observability.profile.ProfileManager
import splice.usage.perf.RequestProjects
import splice.usage.perf.objectives.PerfObjectiveStore
import splice.usage.perf.query.PerfQueryService

/** Operator queries, objective settings and explicit profiles share the daemon's existing sources and identity. */
internal class PerfInspection(paths: StatePaths, identity: PerfIdentity, sources: PerfLiveSources) : AutoCloseable {
    val objectives = PerfObjectiveStore(paths.perfObjectivesFile).also { sources.objectives = it }
    var projects: RequestProjects? = null
    val query = PerfQueryService(sources, RequestProjects { projects?.invoke() ?: EmptyPerfProjects })
    val profiles = ProfileManager(
        paths.perfProfilesDir,
        identity.copy(configRevision = "unknown:effective_configuration_requires_intervals"),
        PerfNativeProfiler.installed(),
    )

    /** Stop captures before alert/delivery readers; outstanding downloads retain their original archive ownership. */
    fun stopProfiles() { profiles.close() }

    /** Objective state remains available to alert/delivery readers until the app has closed them and exports. */
    override fun close() {
        query.close()
        objectives.close()
    }
}
