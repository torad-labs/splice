package splice.usage.perf.query

import io.ktor.http.Parameters
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfScope
import splice.core.util.JsonScalars
import splice.usage.perf.AccountReading
import splice.usage.perf.HeadAccountNames
import splice.usage.perf.PerfRow
import splice.usage.perf.ProjectTags
import splice.usage.perf.RecordedAccounts
import splice.usage.perf.RequestProjects
import splice.usage.perf.TurnsFilterRead
import splice.usage.perf.TurnsFilterReader

/** One bounded query's attribution snapshot, reused for every source and cursor predicate. */
internal class PerfQueryFilter(
    private val query: PerfQuery,
    projects: RequestProjects?,
    private val accounts: RecordedAccounts?,
    private val sources: PerfQuerySources,
) {
    private val tags: ProjectTags? = if (PROJECT in query.filters) projects?.invoke() else null
    private val accountNames = HashMap<String, AccountReading?>()
    private val missingAccounts = HashSet<String>()
    private val legacy = (
        TurnsFilterReader()(
            Parameters.build {
                query.filters.filterKeys { it != SESSION && it != ACCOUNT }.forEach { (k, v) -> append(k, v) }
            },
        ) as? TurnsFilterRead.Read
        )?.filter

    fun unsupported(): String? = when {
        PROJECT in query.filters && tags == null -> "project_mapping_unavailable"
        else -> null
    }

    fun scope(scope: PerfScope): Boolean = scope(scope, emptySet())

    private fun scope(scope: PerfScope, ignored: Set<String>): Boolean = query.filters.filterKeys {
        it !in ignored
    }.all { (key, value) ->
        when (key) {
            "head" -> scope.head == value
            "model" -> scope.model == value
            "transport" -> scope.transport == value
            "outcome" -> outcome(scope.outcome, value)
            "local" -> scope.local == flag(value)
            "compact" -> scope.compact == flag(value)
            "streaming" -> scope.streaming == flag(value)
            else -> true
        }
    }

    fun links(scope: PerfScope, links: PerfLinks): Boolean = links(scope, links, emptySet())

    private fun links(scope: PerfScope, links: PerfLinks, ignored: Set<String>): Boolean =
        query.filters.all { (key, value) ->
            key in ignored || when (key) {
                SESSION -> links.sessionId == value
                PROJECT -> project(links.sessionId, links.projectId, value)
                ACCOUNT -> account(scope.head)?.matches(links.accountId, value) == true
                else -> true
            }
        }

    fun knownMatches(scope: PerfScope, links: PerfLinks, unknown: Set<String>): Boolean =
        scope(scope, unknown) && links(scope, links, unknown)

    fun row(head: String, row: PerfRow): Boolean = row.ts >= query.fromMs && row.ts < query.untilMs &&
        legacy?.matches(row) == true && query.filters.all { (key, value) -> rowFilter(key, value, head, row) }

    private fun rowFilter(key: String, value: String, head: String, row: PerfRow): Boolean = when (key) {
        "head" -> head == value
        SESSION -> row.transcript.sessionId == value
        ACCOUNT -> account(head)?.matches(row.facts.account, value) == true
        PROJECT -> rowProject(row, value)
        "local" -> (row.fields[PerfKeys.LOCAL_STEP] == 1L) == flag(value)
        "compact" -> row.facts.compact == flag(value)
        "transport", "streaming" -> false
        else -> true
    }

    private fun rowProject(row: PerfRow, asked: String): Boolean =
        project(row.facts.session ?: row.transcript.sessionId, null, asked)

    fun accountUnavailable(): String? = if (missingAccounts.isEmpty()) {
        null
    } else if (accounts != null) {
        "suspend_account_port_requires_snapshot_adapter"
    } else {
        "account_alias_mapping_unavailable"
    }

    private fun account(head: String): AccountReading? = accountNames.getOrPut(head) {
        val names = sources.accountNames(head)
        if (names == null) missingAccounts.add(head)
        AccountReading(names ?: HeadAccountNames { null })
    }

    private fun project(session: String?, recorded: String?, asked: String): Boolean {
        val resolved = recorded ?: session?.let { JsonScalars.str(tags?.of(it.take(SESSION_TAG_CHARS)), "id") }
        return if (asked == "none") resolved == null && session != null else resolved == asked
    }

    private fun flag(value: String): Boolean = value == "true" || value == "1"

    private fun outcome(recorded: String?, asked: String): Boolean = when (asked) {
        "failed" -> recorded != null && OutcomeTags.isFailed(recorded)
        "stopped" -> recorded != null && OutcomeTags.isStopped(recorded)
        else -> recorded != null && recorded in asked.split(',')
    }
}

internal val rawFilters =
    setOf(SESSION, PROJECT, ACCOUNT, "silence_ms", "queued_ms", "resumed", "started_over", "test", "unattributed")

private const val PROJECT = "project"
private const val SESSION = "session"
private const val ACCOUNT = "account"
private const val SESSION_TAG_CHARS = 8
