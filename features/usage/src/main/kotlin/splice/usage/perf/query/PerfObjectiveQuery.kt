package splice.usage.perf.query

/** Named objectives carry their stored cohort. Missing lookup must not turn a scoped objective global. */
internal object PerfObjectiveQuery {
    fun resolve(query: PerfQuery, sources: PerfQuerySources): PerfQuery? {
        val id = query.objectiveId ?: return query
        val stored = sources.objective(id)
        val objective = stored ?: query.objective
        val mismatch = query.objective?.let { supplied -> stored != null && supplied != stored } == true
        val filters = filters(query, sources.objectiveCohort(id))
        return if (mismatch || filters == null) {
            null
        } else {
            objective?.takeIf { it.id == id }?.let {
                query.copy(objective = it, filters = filters)
            }
        }
    }

    private fun filters(query: PerfQuery, cohort: Map<String, String>?): Map<String, String>? {
        if (cohort == null || cohort.size > allowed.size) return null
        val valid = cohort.keys.all { it in allowed } &&
            PerfQueryParser.parse(cohort + mapOf("from" to "0", "until" to "1"), 1).error == null
        val conflict = cohort.any { (key, value) ->
            query.filters[key]?.let { !same(key, value, it) } == true
        }
        return if (valid && !conflict) cohort + query.filters else null
    }

    private fun same(key: String, first: String, second: String): Boolean =
        if (key in flags) (first in trueValues) == (second in trueValues) else first == second

    private val flags = setOf("local", "compact", "streaming")
    private val trueValues = setOf("1", "true")
    private val allowed = flags + setOf("head", "model", "transport", "outcome")
}
