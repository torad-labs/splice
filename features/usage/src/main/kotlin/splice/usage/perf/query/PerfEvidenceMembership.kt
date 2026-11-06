package splice.usage.perf.query

import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfScope

/** Unknown fields only affect a cohort when every known selector could still match. */
internal object PerfEvidenceMembership {
    fun unknown(events: List<PerfObservation>, query: PerfQuery, filter: PerfQueryFilter): Boolean {
        if (events.none { PerfEvidenceWindow.inside(it, query) }) return false
        val representative = PerfEvidenceSelection.representative(events)
        val scope = representative.scope.copy(outcome = representative.outcome ?: representative.scope.outcome)
        val absent = (missingScope(scope) + missingLinks(representative.links))
            .filterValues { it }.keys.intersect(query.filters.keys)
        return absent.isNotEmpty() && filter.knownMatches(scope, representative.links, absent)
    }

    private fun missingScope(scope: PerfScope): Map<String, Boolean> = mapOf(
        "head" to (scope.head == "unknown"),
        "model" to (scope.model == null || scope.model == "unknown"),
        "transport" to (scope.transport == null || scope.transport == "unknown"),
        "outcome" to (scope.outcome == null),
        "local" to (scope.local == null),
        "compact" to (scope.compact == null),
        "streaming" to (scope.streaming == null),
    )

    private fun missingLinks(links: PerfLinks): Map<String, Boolean> = mapOf(
        "session" to (links.sessionId == null),
        "project" to (links.sessionId == null && links.projectId == null),
        "account" to (links.accountId == null),
    )
}
