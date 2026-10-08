// PORT-OF: splice/gateway/head/HeadServer.kt (handleMessages) @ 1caedd6 — invariants unchanged:
// admission is acquired BEFORE the body is read, so a queued call retains no transcript and only
// an admitted call may enter the materialization gate; the slot is released in a NonCancellable
// finally (leak-safe teardown); a body-parse failure is a client 400, never a crash. Split out
// (HD-24) as the orchestration the old file header named as its identity, now with nothing else
// in the file.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import splice.core.perf.OutcomeTag
import splice.core.util.WallClock
import splice.head.ClientAuth
import splice.head.HeadDeps
import splice.head.turn.Preparation
import splice.head.turn.SESSION_TAG_CHARS
import splice.head.turn.TurnDriver
import splice.head.turn.TurnInputs
import splice.head.turn.TurnPreparation
import splice.head.wire.TurnTrace
import splice.upstream.TurnEnd
import splice.upstream.credentials.Selection

internal class HeadAdmission(
    private val deps: HeadDeps,
    private val clientAuth: ClientAuth,
    private val admission: AdmissionGate,
    private val telemetry: AdmissionTelemetry,
    private val preparation: TurnPreparation,
    private val responses: AdmissionResponses,
    private val driver: TurnDriver,
) {
    // V4-50 reads a WALL instant because a reset is a calendar fact the client must be told in epoch
    // seconds; [HeadDeps.clock] is an ElapsedClock and cannot answer that. The two admissions below take
    // the clock as their own parameter, which is where a test injects one to refuse without sleeping.
    private val wallClock = WallClock(System::currentTimeMillis)
    private val credentialHolds = CredentialHoldAdmission(preparation.provider, deps, responses, driver, wallClock)
    private val exhaustedAccounts = ExhaustedAccountAdmission(deps, responses, driver, wallClock)

    fun arrivalTime(): Long = telemetry.arrivalTime()

    suspend fun handleMessages(call: ApplicationCall, arrivalAt: Long = arrivalTime()) {
        if (!clientAuth.authorizeUpstream(call) || !admission.acceptingOrRespond(call)) return
        val perf = telemetry.begin(arrivalAt)
        val t0 = deps.seams.clock()
        val slot = admission.acquireSlotOrRespond(call) ?: return
        telemetry.markAdmitted(perf, t0)
        // A detached compaction takes the slot with it (TurnStreamer.driveDetachable): the drive
        // releases it when the upstream turn ends, not this call when its client has gone. The flag
        // is this call's from the start, never a return value: the drive's cancelled-call path
        // THROWS out of serve (review of PR 137), and a finally must not read the flag off a call
        // that never returned.
        val admitted = AdmittedTurn(slot, t0, perf)

        try {
            // Prepared request trees survive decode and may belong to a detached compaction.
            // Their heap lease follows the same slot that already owns that drive's lifetime.
            val owner = MaterializationOwner { admitted.materializedEnd = it }
            val leaseStart = telemetry.arrivalTime()
            val prepared = admission.materializeOrRespond(
                call,
                owner = owner,
                beforeRefusal = TurnEnd(admitted::release),
            ) {
                telemetry.prepare(perf, leaseStart) { preparation.prepareTurn(call, perf) }
            } ?: return
            if (admitted.settle(call, prepared, admission)) serve(call, prepared, admitted)
        } finally {
            admitted.close()
        }
    }

    private suspend fun serve(call: ApplicationCall, prepared: Preparation, admitted: AdmittedTurn) {
        // Only a Ready turn names a live row. settle already returned Local/Replay ownership,
        // including a borrowed handle whose row still belongs to its independent source.
        when (prepared) {
            is Preparation.Rejected -> responses.respondInvalidRequest(call, prepared.message)
            is Preparation.Local -> driver.answerLocally(call, prepared)
            is Preparation.Replay -> {
                // settle returned this retry's candidate before it follows the independently held drive.
                driver.replay(call, prepared)
            }
            is Preparation.Ready -> {
                val meta = prepared.built.meta
                admitted.slot.describe(meta.upstreamModel, meta.compact, meta.sessionId?.take(SESSION_TAG_CHARS))
                // V4-165: the turn ends when its admission slot is released — here on a refusal or
                // an attached drive, inside TurnStreamer for a detached one. One registration
                // covers every exit, because the slot already has to be released on each of them.
                prepared.built.onEnd?.let(admitted.slot::onRelease)
                // V4-319: a streaming turn is listed, and the operator can stop it, from here until
                // the same release ends it. A collect has no open stream a stop could end with a frame.
                if (prepared.stream) deps.liveTurns.admitted(admitted.slot, meta, prepared.messagesHash)
                serveReady(call, prepared, admitted)
            }
        }
    }

    /** A 400 before SSE, so the installed client's conditional size-error path can compact.
     * This is an estimated-input bound with a measured append delta, plus empirical p99 output. */
    private suspend fun refuseIfOversized(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
    ): Boolean {
        val meta = prepared.built.meta
        val message = admission.compactionPreflight.refusal(meta, prepared.built.requestBody, prepared.hasPriorExchange)
            ?: return false
        val tag = when {
            meta.compact -> OutcomeTag.COMPACTION_PREFLIGHT_COMPACT_OVERFLOW
            prepared.hasPriorExchange -> OutcomeTag.COMPACTION_PREFLIGHT_COMPACTABLE
            else -> OutcomeTag.COMPACTION_PREFLIGHT_FIRST_EXCHANGE
        }
        trace?.failureSentence(message)
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(tag.wire, message, trace),
        )
        call.response.header("x-should-retry", "false")
        admitted.close()
        responses.respondInvalidRequest(call, message)
        return true
    }

    /** V4-133 review: A REACHED `block` BUDGET REFUSES THE TURN HERE, for the reason the rate-limit
     *  refusal below sits here: before a response is committed, while the status line is still ours.
     *  The head's budget ([HeadDeps.HeadQuota.budget]) decides; this only answers for it.
     *
     *  403 permission_error, never 429: the refusal lasts until the UTC day turns, and a 429 invites
     *  the client to retry into the same refusal. Claude Code does not retry a 403 and shows its
     *  message, so the budget's own sentence — head, spend, limit, when it lifts — is what the
     *  operator reads. Recorded BEFORE responding, like every local refusal (V4-55). A budget that
     *  is not reached, or only warns, admits the turn and changes nothing on its path. */
    private suspend fun refuseIfOverBudget(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
    ): Boolean {
        val block = deps.quotaBundle.budget.admit() ?: return false
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(OutcomeTag.BUDGET_BLOCKED.wire, block.detail, trace),
        )
        admitted.close()
        responses.respondBudgetBlocked(call, block.message)
        return true
    }

    private suspend fun serveReady(call: ApplicationCall, prepared: Preparation.Ready, admitted: AdmittedTurn) {
        // V4-134: turn.start fires HERE and nowhere earlier because this is the one path whose every
        // exit writes a perf row — the two local refusals below and the drive all go through
        // TurnTelemetry's emitters, which fire turn.end. Rejected, Local and Replay write no row, so
        // announcing them would leave the console a start with no end.
        deps.seams.events.turnStarted(prepared.built.meta.sessionId)
        // V4-174: the trace begins HERE, before the two local refusals, because a refused turn is a
        // request the head received and answered — a trace that skipped it would show a client
        // retrying for no visible reason. Null for every head whose trace is off.
        val trace = prepared.takeInbound()?.let { deps.stores.trace?.begin(prepared.built.meta, it) }
        if (refuseIfOverBudget(call, prepared, admitted, trace)) return
        var account = when (val selection = deps.quotaBundle.activePool?.select(prepared.built.meta.sessionId)) {
            null -> null
            is Selection.Chosen -> selection.account
            is Selection.Exhausted -> {
                exhaustedAccounts.refuse(call, prepared, admitted, selection, trace)
                return
            }
        }
        try {
            when (val hold = credentialHolds.admit(call, prepared, admitted, trace, account)) {
                is CredentialHoldAdmission.Outcome.Allowed -> {
                    account = hold.account
                    driveReady(call, prepared, admitted, trace, account)
                }
                CredentialHoldAdmission.Outcome.Refused -> Unit
            }
        } finally {
            if (!admitted.wasHandedOff()) account?.releaseCredentialProbe()
        }
    }

    private suspend fun driveReady(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
        account: splice.upstream.credentials.AccountSelection?,
    ) {
        if (refuseIfOversized(call, prepared, admitted, trace)) return
        admitted.retainRequest()
        val inputs = TurnInputs(
            prepared.built,
            admitted.slot,
            admitted.t0,
            admitted.perf,
            markHandedOff = { admitted.markHandedOff() },
            trace = trace,
            account = account,
            quota = deps.turnQuota.forSession(prepared.built.meta.sessionId, account),
        )
        if (prepared.stream) driver.stream(call, inputs) else driver.collect(call, inputs)
    }
}

// Seconds on the HTTP retry wire use the same conversion in both credential and exhausted-pool refusals.
internal const val MILLIS_PER_SECOND = 1000L
