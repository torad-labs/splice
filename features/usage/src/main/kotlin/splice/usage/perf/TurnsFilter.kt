// V4-444: which rows of a head's window one GET /api/perf/turns asks for. The console's Requests list
// filters HERE, over the whole window and before the newest-n clamp. A filter run in the browser over
// the slice the route already cut finds nothing older than the slice: the header counted 3 failed
// requests in the last hour while "Failed" listed none.
package splice.usage.perf

import io.ktor.http.Parameters
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys

/** The outcome a request may ask for besides an exact tag: every row that ended anywhere but ok.
 *  The unattributed `?` is unknown, not failed, the same split [PerfSummary] counts its failures by. */
private const val FAILED = "failed"

/** A field a link may ask to be missing, so the requests nothing attributed can be listed without inventing a name. */
internal enum class Unattributed(val wire: String) { MODEL("model"), ACCOUNT("account") }

/** What one request asked of the window's rows. A null field asks nothing; [local] false leaves out the steps splice
 *  answered itself, which are not model requests. */
internal data class TurnsFilter(
    val until: Long? = null,
    val outcome: String? = null,
    val model: String? = null,
    val account: String? = null,
    val session: String? = null,
    val unattributed: Unattributed? = null,
    val local: Boolean = true,
) {
    fun matches(row: PerfRow): Boolean = listOf(
        until == null || row.ts < until,
        outcomeMatches(row.outcome),
        model == null || row.model == model,
        account == null || row.account == account,
        session == null || row.session == session,
        attributionMatches(row),
        local || row.fields[PerfKeys.LOCAL_STEP] != 1L,
    ).all { it }

    private fun outcomeMatches(tag: String): Boolean = when (outcome) {
        null -> true
        FAILED -> tag != OutcomeTag.OK.wire && tag != UNATTRIBUTED_OUTCOME
        else -> tag == outcome
    }

    private fun attributionMatches(row: PerfRow): Boolean = when (unattributed) {
        null -> true
        Unattributed.MODEL -> row.model == null
        Unattributed.ACCOUNT -> row.account == null
    }
}

/** A request's filter, or the sentence naming the parameter it could not read. */
internal sealed class TurnsFilterRead {
    data class Read(val filter: TurnsFilter) : TurnsFilterRead()

    data class Refused(val message: String) : TurnsFilterRead()
}

/** Reads the filter parameters of one request. A value spelled wrong is REFUSED by name, never
 *  ignored: ignoring it would answer the unfiltered window while looking exactly like the filtered one. */
internal class TurnsFilterReader {
    operator fun invoke(params: Parameters): TurnsFilterRead {
        val untilText = params["until"]
        val until = untilText?.toLongOrNull()?.takeIf { it >= 0 }
        val unattributedText = params["unattributed"]
        val unattributed = Unattributed.entries.firstOrNull { it.wire == unattributedText }
        val localText = params["local"]
        val local = when (localText) {
            null, "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        val problem = listOfNotNull(
            "until must be a non-negative epoch-ms instant, got '$untilText'"
                .takeIf { untilText != null && until == null },
            "unattributed must be model or account, got '$unattributedText'"
                .takeIf { unattributedText != null && unattributed == null },
            "local must be 0 or 1, got '$localText'".takeIf { local == null },
        ).firstOrNull()
        val filter = TurnsFilter(
            until = until,
            outcome = given(params["outcome"]),
            model = given(params["model"]),
            account = given(params["account"]),
            session = given(params["session"]),
            unattributed = unattributed,
            local = local ?: true,
        )
        return if (problem == null) TurnsFilterRead.Read(filter) else TurnsFilterRead.Refused(problem)
    }

    /** A parameter given empty asks nothing, the same as one left out. */
    private fun given(value: String?): String? = value?.takeIf { it.isNotEmpty() }
}
