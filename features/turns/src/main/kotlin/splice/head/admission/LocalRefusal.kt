// NEW: V4-174 — a turn refused by the head itself, before any upstream call (the armed rate-limit
// cooldown, every pooled account exhausted): the perf row's tag, the log line's detail, and the
// turn's trace when the head's is on, so the refusal is on record beside the turns that ran.
// One value instead of three more parameters on recordLocalRefusal (detekt's LongParameterList).
//
// [PreDriveRefusals] holds the refusals a Ready turn meets after its account is known and before it is driven: the
// head's reached budget, the compaction preflight's size bound, and another machine's word with no login held for it,
// and (2026-11-09) the refusal of a request while it was prepared, which was answered and never recorded.
// Each answers while the status line is still ours and records its row before responding. The first two moved here
// from HeadAdmission (concentration, Oct 12).
package splice.head.admission

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.auth.AuthProvider
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.PreflightKind
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.head.HeadDeps
import splice.head.HeldLoginVerdict
import splice.head.transport.CallerCredential
import splice.head.turn.Preparation
import splice.head.turn.RefusedRequest
import splice.head.turn.SESSION_TAG_CHARS
import splice.head.turn.TurnDriver
import splice.head.wire.TurnTrace
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.Selection

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
    private val loginCommand: String,
    /** The head's run of refusals before any upstream turn (2026-11-09 audit): each prepared refusal counts. */
    private val streak: RefusalStreak,
) {
    /** A request refused while it was prepared ([Preparation.Rejected]): a model the head does not serve, a body that
     *  did not parse, a control the dialect cannot carry, a stopped turn's re-send, or a defect in preparing it. It is
     *  recorded as every other local refusal is, before the answer: one daemon.log line naming the head, the line the
     *  client reads (the declined feature), the session and the client, a perf row, turn.start and turn.end, and the
     *  trace when the head's is on. Until 2026-11-09 it was answered and nothing else: from 5:51 to 6:58 PM CT that
     *  day the Responses dialect refused every claudex turn on Claude Code's new `diagnostics` field, and the log, the
     *  perf file and the trace showed a quiet head. A defect is recorded as unexpected, every other cause as refused. */
    suspend fun refusePrepared(call: ApplicationCall, rejected: Preparation.Rejected, admitted: AdmittedTurn) {
        val cause = rejected.cause
        val request = rejected.request
        val meta = request.meta()
        RequestObservation.refused(admitted.perf, request.model, request.sessionId, request.stream)
        // A refusal writes a row, so it is announced like the turns that run: the console sees a start and its end.
        deps.seams.events.turnStarted(request.sessionId)
        val trace = request.inbound?.let { deps.stores.captures.trace?.begin(meta, it) }
        RequestObservation.turn(admitted.perf, trace?.turnId)
        val sentence = responses.lineOf(cause).sentence
        trace?.failureSentence(sentence)
        // A stopped turn's re-send is the operator's own stop answered, not the head refusing its clients.
        if (cause !is FailureCause.Splice.Stopped) streak.refused(sentence, request.detail)
        val defect = cause is FailureCause.Splice.Internal
        val tag = if (defect) OutcomeTag.UNEXPECTED.wire else OutcomeTags.failure(cause)
        val refusal = LocalRefusal(tag, said(call, request, sentence), trace)
        driver.recordLocalRefusal(meta, admitted.perf, admitted.t0, refusal)
        admitted.close()
        responses.respondRefusal(call, cause, if (defect) OutcomeTag.UNEXPECTED else null)
    }

    /** The refusal's log detail: what splice saw, the line the client reads, and who sent it. The client's User-Agent
     *  names its version, which is what tells a client update apart from a broken head. */
    private fun said(call: ApplicationCall, request: RefusedRequest, sentence: String): String {
        val session = request.sessionId?.let { "session ${it.take(SESSION_TAG_CHARS)}, " }.orEmpty()
        val client = call.request.headers[HttpHeaders.UserAgent]?.take(CLIENT_AGENT_CHARS) ?: "no user-agent"
        val seen = request.detail?.let { "$it: " }.orEmpty()
        return "refused before any upstream turn: $seen$sentence ($session$client)"
    }

    /** Selection can fail only because no credential is usable, or an owner action is already being probed.
     *  Provider limits select their earliest-reset login and are handled by that turn's retry engine. */
    suspend fun refuseNoLogin(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        selection: Selection.Exhausted,
        trace: TurnTrace?,
    ) {
        val blocked = selection.blocked
        val cause = if (blocked == null) {
            FailureCause.Splice.NoLogin(loginCommand)
        } else {
            FailureCause.Provider.Forbidden(block = blocked)
        }
        val sentence = responses.lineOf(cause).sentence
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(OutcomeTags.failure(cause), sentence, trace, blocked?.login),
        )
        admitted.close()
        responses.respondRefusal(call, cause)
    }

    /** A 400 before SSE, so the installed client's conditional size-error path can compact.
     * This is an estimated-input bound with a measured append delta, plus empirical p99 output. */
    suspend fun refuseIfOversized(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
    ): Boolean {
        val meta = prepared.built.meta
        val refused = admission.compactionPreflight.refusal(meta, prepared.built.requestBody, prepared.hasPriorExchange)
            ?: return false
        val (tag, kind) = when {
            meta.compact -> OutcomeTag.COMPACTION_PREFLIGHT_COMPACT_OVERFLOW to PreflightKind.COMPACT_OVERFLOW
            prepared.hasPriorExchange -> OutcomeTag.COMPACTION_PREFLIGHT_COMPACTABLE to PreflightKind.COMPACTABLE
            else -> OutcomeTag.COMPACTION_PREFLIGHT_FIRST_EXCHANGE to PreflightKind.FIRST_EXCHANGE
        }
        val cause = FailureCause.Splice.Declined(Decline.Preflight(kind, refused.windowTokens))
        // The trace says what the client reads; the log and the perf row keep the bound's own numbers.
        trace?.failureSentence(responses.lineOf(cause).sentence)
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(tag.wire, refused.detail, trace),
        )
        admitted.close()
        // x-should-retry false (ClientWire): the client answers this bound by compacting, not by re-sending.
        responses.respondRefusal(call, cause)
        return true
    }

    /** V4-133 review: A REACHED `block` BUDGET REFUSES THE TURN HERE, for the reason the credential-hold
     *  refusal sits in HeadAdmission: before a response is committed, while the status line is still ours.
     *  The head's budget ([HeadDeps.HeadQuota.budget]) decides; this only answers for it.
     *
     *  403 permission_error, never 429: the refusal lasts until local midnight, and a 429 invites
     *  the client to retry into the same refusal. Claude Code does not retry a 403 and shows its
     *  message, so the budget's line (the head, its limit and when it resets) is what the
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
        responses.respondBudgetBlocked(call, block)
        return true
    }

    /** PR #247 review round 2, Oct 30 (security): on a head that forwards the caller's login, [pool], the one this
     *  turn selects from, holds a login of the operator's, and the door did not prove the caller splice's. The door
     *  let it through while the head held none, and one joined while its body arrived. It is refused as the door
     *  refuses it, 401 with the way back, and nothing of it reaches the vendor. */
    suspend fun refuseIfUnproven(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
        pool: AccountPool?,
    ): Boolean {
        val heldForAnother = pool != null && deps.policy.forwardClientAuth
        if (!heldForAnother || HeldLoginVerdict.proven(call)) return false
        val cause = FailureCause.Splice.Declined(Decline.NotLaunched)
        val sentence = responses.lineOf(cause).sentence
        trace?.failureSentence(sentence)
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(OutcomeTags.failure(cause), sentence, trace),
        )
        admitted.close()
        responses.respondNotLaunched(call)
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
        val cause = FailureCause.Splice.Declined(Decline.NoSharedLogin)
        val sentence = responses.lineOf(cause).sentence
        trace?.failureSentence(sentence)
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(OutcomeTag.AUTH_MISSING.wire, sentence, trace),
        )
        // 503, not retried (ClientWire): a login coming back here is this machine's to do, so the node shows the
        // line at once.
        admitted.close()
        responses.respondRefusal(call, cause)
        return true
    }
}

// why: a User-Agent names the client and its version in a few dozen characters; one past this is cut, never quoted whole.
private const val CLIENT_AGENT_CHARS = 96
