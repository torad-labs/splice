// NEW: Oct 10, 2026 — split from SessionsWiring for the push gate (Concentration), and kept out of mount, which is at
// its package ceiling: the per-session facts the daemon reads off each head's perf rows as they are appended, the turn
// count and how the newest request ended, for /api/sessions rows.
package splice.app.control

import splice.app.sources.PerfStatsSource
import splice.core.perf.OutcomeTag
import splice.sessions.http.SessionEnding
import splice.sessions.http.SessionEndingOf
import splice.sessions.http.SessionTurnCount
import splice.sessions.http.SessionTurnsOf
import java.util.concurrent.TimeUnit

/** [ports] is read at CALL time, the SessionsWiring rule. */
internal class SessionPerfFacts(private val heads: Map<String, ManagedHead>, private val ports: ConsolePorts) {
    /** Every head's per-session accumulator, summed, because a session that moved heads has rows on
     *  both and one head's count would read as the whole of it. The combined start is the LATEST of
     *  the heads that contributed (SessionTurnCount): the sum covers the session only where every one
     *  of those counters was already running when it began, so the conservative start is the honest
     *  one. A head with no counted row for the session contributes nothing and does not move the
     *  start, and no total anywhere leaves the row's `turns` absent rather than zero. */
    fun turnsOf(): SessionTurnsOf = SessionTurnsOf { id ->
        val counted = heads.values
            .mapNotNull { (it.sources.perf as? PerfStatsSource)?.sessionTotals }
            .mapNotNull { store -> store.totalFor(id) }
        if (counted.isEmpty()) {
            null
        } else {
            SessionTurnCount(
                turns = counted.sumOf { total -> total.models.values.sumOf { it.turns } },
                fromMs = counted.maxOf { it.fromMs },
            )
        }
    }

    /** How the session's newest request ended, newest across every head, and only when that ending holds the session
     *  back until something changes: a plan window or every account spent (At limit), or no credential (Signed
     *  out). A burst 429 passes on its own, so it is not one. */
    private val holdingEndings =
        setOf(OutcomeTag.PLAN_LIMIT, OutcomeTag.ALL_ACCOUNTS_EXHAUSTED, OutcomeTag.AUTH_MISSING)
            .mapTo(HashSet()) { it.wire }

    fun endingOf(): SessionEndingOf = SessionEndingOf { id ->
        heads.mapNotNull { (key, head) ->
            (head.sources.perf as? PerfStatsSource)?.sessionEndings?.endingFor(id)?.let { key to it }
        }
            .maxByOrNull { (_, ended) -> ended.ts }
            ?.takeIf { (_, ended) -> ended.outcome in holdingEndings }
            ?.let { (head, ended) ->
                val resetMs = ended.resetEpochSeconds?.let(TimeUnit.SECONDS::toMillis)
                // in the Accounts roster's words: a client head's row carries the login's identity, not its label
                val account = ended.account?.let { ports.claudeLogins?.accountLabel(head, it) ?: it }
                SessionEnding(ended.outcome, account, resetMs, ended.ts)
            }
    }
}
