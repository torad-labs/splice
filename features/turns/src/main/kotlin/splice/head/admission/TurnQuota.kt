// NEW: V4-99 (arch-audit 2026-09-17) — the sole resolver for which QuotaTracker a turn reads.
//
// "Which quota tracker does this turn read?" used to be answered with an elvis chain at seven call
// sites, three different left operands, two right operands. The precedence is selected-account
// tracker, else the head's primary — and `?:` types nothing about that, so a site reaching for
// deps.quotaBundle.quota first compiles, runs, and stamps the WRONG account's anthropic-ratelimit-* headers.
// HeadAdmission.kt once carried a hand-written comment recording that exact bug fixed at ONE site
// while six siblings kept the old shape. This file is the single place the decision lives.
package splice.head.admission

import splice.head.usage.QuotaTracker
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountSelection

/** The one resolver: the SELECTED account's tracker if a selection (or a sticky session) is in
 *  hand, else the head's primary tracker. Owned by [HeadDeps] and read through
 *  [HeadDeps.turnQuota], so no caller re-derives the chain. */
internal class TurnQuota(
    private val accountPool: AccountPool?,
    private val accountQuotas: Map<String, QuotaTracker>,
    private val primary: QuotaTracker?,
) {
    fun forSession(sessionId: String?, account: AccountSelection?): QuotaTracker? {
        val label = account?.account?.label ?: accountPool?.view(sessionId)?.selectedLabel
        return label?.let(accountQuotas::get) ?: primary
    }
}
