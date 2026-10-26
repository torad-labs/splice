// NEW: V4-174 — a turn refused by the head itself, before any upstream call (the armed rate-limit
// cooldown, every pooled account exhausted): the perf row's tag, the log line's detail, and the
// turn's trace when the head's is on, so the refusal is on record beside the turns that ran.
// One value instead of three more parameters on recordLocalRefusal (detekt's LongParameterList).
//
// [PreDriveRefusals] holds the refusals a Ready turn meets after its account is known and before it is driven: the
// head's reached budget, the compaction preflight's size bound, and another machine's word with no login held for it.
// Each answers while the status line is still ours and records its row before responding. The first two moved here
// from HeadAdmission (concentration, Oct 12).
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import splice.core.auth.AuthProvider
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.PreflightKind
import splice.core.perf.OutcomeTag
import splice.head.HeadDeps
import splice.head.transport.CallerCredential
import splice.head.turn.Preparation
import splice.head.turn.TurnDriver
import splice.head.wire.TurnTrace

internal data class LocalRefusal(
    val tag: String,
    val detail: String,
    val trace: TurnTrace?,
    val account: String? = null,
)

internal class PreDriveRefusals(
    private val deps: HeadDeps,
    private val admission: AdmissionGate,
    private val responses: AdmissionResponses,
    private val driver: TurnDriver,
) {
    /** A 400 before SSE, so the installed client's conditional size-error path can compact.
     * This is an estimated-input bound with a measured append delta, plus empirical p99 output. */
    suspend fun refuseIfOversized(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
    ): Boolean {
        val meta = prepared.built.meta
        val message = admission.compactionPreflight.refusal(meta, prepared.built.requestBody, prepared.hasPriorExchange)
            ?: return false
        val (tag, kind) = when {
            meta.compact -> OutcomeTag.COMPACTION_PREFLIGHT_COMPACT_OVERFLOW to PreflightKind.COMPACT_OVERFLOW
            prepared.hasPriorExchange -> OutcomeTag.COMPACTION_PREFLIGHT_COMPACTABLE to PreflightKind.COMPACTABLE
            else -> OutcomeTag.COMPACTION_PREFLIGHT_FIRST_EXCHANGE to PreflightKind.FIRST_EXCHANGE
        }
        trace?.failureSentence(message)
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(tag.wire, message, trace),
        )
        admitted.close()
        // x-should-retry false (ClientWire): the client answers this bound by compacting, not by re-sending.
        responses.respondRefusal(call, FailureCause.Splice.Declined(Decline.Preflight(kind)), message)
        return true
    }

    /** V4-133 review: A REACHED `block` BUDGET REFUSES THE TURN HERE, for the reason the credential-hold
     *  refusal sits in HeadAdmission: before a response is committed, while the status line is still ours.
     *  The head's budget ([HeadDeps.HeadQuota.budget]) decides; this only answers for it.
     *
     *  403 permission_error, never 429: the refusal lasts until local midnight, and a 429 invites
     *  the client to retry into the same refusal. Claude Code does not retry a 403 and shows its
     *  message, so the budget's own sentence — head, spend, limit, when it lifts — is what the
     *  operator reads. Recorded BEFORE responding, like every local refusal (V4-55). A budget that
     *  is not reached, or only warns, admits the turn and changes nothing on its path. */
    suspend fun refuseIfOverBudget(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
        keyAccount: KeyNaming,
    ): Boolean {
        val block = deps.quotaBundle.budget.admit() ?: return false
        val named = (keyAccount as? KeyNaming.Named)?.label
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(OutcomeTag.BUDGET_BLOCKED.wire, block.detail, trace, named),
        )
        admitted.close()
        responses.respondBudgetBlocked(call, block.message)
        return true
    }

    /** LAN share: another machine's turn holds its word, which opens nothing, and runs on a login this head holds. When
     *  the login admission settled on, [sender], would forward the caller's credential instead, the word is answered
     *  here and never sent: the vendor's 401 would mark this head's own sign-in rejected for every session. */
    suspend fun refuseIfNodeWordUnheld(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
        sender: AuthProvider,
    ): Boolean {
        if (!CallerCredential.unheldNodeWord(prepared.built.extraHeaders, sender)) return false
        val message = "another machine's turn runs on a Claude login this machine holds, and this head has none free " +
            "for it, so the turn was not sent; it runs once one of this machine's own logins is back on this head"
        trace?.failureSentence(message)
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(OutcomeTag.AUTH_MISSING.wire, message, trace),
        )
        // 503, not retried (ClientWire): a login coming back here is this machine's to do, so the node shows the
        // sentence at once.
        admitted.close()
        responses.respondRefusal(call, FailureCause.Splice.Declined(Decline.NoSharedLogin), message)
        return true
    }
}
