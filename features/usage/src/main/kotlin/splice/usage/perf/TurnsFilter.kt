// NEW: V4-444, which rows of a head's window one GET /api/perf/turns asks for. The console's Requests list
// filters HERE, over the whole window and before the newest-n clamp. A filter run in the browser over
// the slice the route already cut finds nothing older than the slice: the header counted 3 failed
// requests in the last hour while "Failed" listed none.
package splice.usage.perf

import io.ktor.http.Parameters
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfKeys

/** Read-side selectors besides an exact recorded tag, shared with the summary classification. */
private const val FAILED = "failed"
private const val STOPPED = "stopped"

/** A field a link may ask to be missing, so the requests nothing attributed can be listed without inventing a name. */
internal enum class Unattributed(val wire: String) { MODEL("model"), ACCOUNT("account") }

/** What a request WAITED THROUGH, so the pages that name a wait can open the requests that had it (BUILD.md: "the
 *  silences waited out and the answers resumed", "the requests that waited in line"). Each is a floor in milliseconds
 *  or a flag, and each reads the counter the turn already recorded:
 *
 *   [silenceMs]  the longest upstream silence this request sat through (UP_GAP_MAX_MS), at or over this
 *   [queuedMs]   how long it waited for a free slot at its command's limit (ADMIT_WAIT_MS), at or over this
 *   [resumed]    whether splice re-anchored it at all (REANCHORS), true for the ones it did and false for the rest
 *
 *  A row that never carried the counter waited through nothing, so it reads as zero and a floor above zero leaves it
 *  out — which is the question "show me the ones that waited" asking for exactly the ones that did. */
internal data class TurnsWaits(
    val silenceMs: Long? = null,
    val queuedMs: Long? = null,
    val resumed: Boolean? = null,
) {
    fun matches(row: PerfRow): Boolean = listOf(
        silenceMs == null || counted(row, PerfKeys.UP_GAP_MAX_MS) >= silenceMs,
        queuedMs == null || counted(row, PerfKeys.ADMIT_WAIT_MS) >= queuedMs,
        resumed == null || (counted(row, PerfKeys.REANCHORS) > 0) == resumed,
    ).all { it }

    private fun counted(row: PerfRow, key: String): Long = row.fields[key] ?: 0L
}

/** Which attribution a filter asks for: an exact model, account or session, or the requests nothing attributed. */
internal data class TurnsAttribution(
    val model: String? = null,
    val account: String? = null,
    val session: String? = null,
    val unattributed: Unattributed? = null,
)

/** What one request asked of the window's rows. A null field asks nothing; [local] false leaves out the steps splice
 *  answered itself, which are not model requests; [compact] true asks for the compactions and false for every request
 *  that was not one, a row that never carried the field included. */
internal data class TurnsFilter(
    val until: Long? = null,
    val outcome: String? = null,
    val attribution: TurnsAttribution = TurnsAttribution(),
    val local: Boolean = true,
    val compact: Boolean? = null,
    val waits: TurnsWaits = TurnsWaits(),
) {
    fun matches(row: PerfRow): Boolean = listOf(
        until == null || row.ts < until,
        outcomeMatches(row.outcome),
        attribution.model == null || row.facts.model == attribution.model,
        attribution.account == null || row.facts.account == attribution.account,
        attribution.session == null || row.facts.session == attribution.session,
        attributionMatches(row),
        local || row.fields[PerfKeys.LOCAL_STEP] != 1L,
        compact == null || (row.facts.compact == true) == compact,
        waits.matches(row),
    ).all { it }

    private fun outcomeMatches(tag: String): Boolean = when (outcome) {
        null -> true
        FAILED -> OutcomeTags.isFailed(tag)
        STOPPED -> OutcomeTags.isStopped(tag)
        else -> tag == outcome
    }

    private fun attributionMatches(row: PerfRow): Boolean = when (attribution.unattributed) {
        null -> true
        Unattributed.MODEL -> row.facts.model == null
        Unattributed.ACCOUNT -> row.facts.account == null
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
        val compactText = params["compact"]
        val problem = listOfNotNull(
            "until must be a non-negative epoch-ms instant, got '$untilText'"
                .takeIf { untilText != null && until == null },
            "unattributed must be model or account, got '$unattributedText'"
                .takeIf { unattributedText != null && unattributed == null },
            "local must be 0 or 1, got '$localText'".takeIf { localText != null && switch(localText) == null },
            "compact must be 0 or 1, got '$compactText'".takeIf { compactText != null && switch(compactText) == null },
            waitProblem(params),
        ).firstOrNull()
        val filter = TurnsFilter(
            until = until,
            outcome = given(params["outcome"]),
            attribution = TurnsAttribution(
                model = given(params["model"]),
                account = given(params["account"]),
                session = given(params["session"]),
                unattributed = unattributed,
            ),
            local = switch(localText) ?: true,
            compact = switch(compactText),
            waits = TurnsWaits(
                silenceMs = floor(params["silence_ms"]),
                queuedMs = floor(params["queued_ms"]),
                resumed = switch(params["resumed"]),
            ),
        )
        return if (problem == null) TurnsFilterRead.Read(filter) else TurnsFilterRead.Refused(problem)
    }

    /** The first wait parameter spelled wrong, named, or null when all three read. Its own function so the reader's
     *  one entry point stays the list of refusals it already is. */
    private fun waitProblem(params: Parameters): String? {
        val silence = params["silence_ms"]
        val queued = params["queued_ms"]
        val resumed = params["resumed"]
        return listOfNotNull(
            "silence_ms must be a non-negative whole number of milliseconds, got '$silence'"
                .takeIf { silence != null && floor(silence) == null },
            "queued_ms must be a non-negative whole number of milliseconds, got '$queued'"
                .takeIf { queued != null && floor(queued) == null },
            "resumed must be 0 or 1, got '$resumed'".takeIf { resumed != null && switch(resumed) == null },
        ).firstOrNull()
    }

    /** A floor in milliseconds, or null when it is absent or is not a whole number at or above zero. */
    private fun floor(text: String?): Long? = text?.toLongOrNull()?.takeIf { it >= 0 }

    /** A 0-or-1 switch as the boolean it names, or null when it is absent or spelled any other way. */
    private fun switch(text: String?): Boolean? = when (text) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    /** A parameter given empty asks nothing, the same as one left out. */
    private fun given(value: String?): String? = value?.takeIf { it.isNotEmpty() }
}
