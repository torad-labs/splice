// PORT-OF: splice/gateway/head/HeadServer.kt (handleMessages) @ 1caedd6 — invariants unchanged:
// admission is acquired BEFORE the body is read, so a queued call retains no transcript and only
// an admitted call may enter the materialization gate; the slot is released in a NonCancellable
// finally (leak-safe teardown); a body-parse failure is a client 400, never a crash. Split out
// (HD-24) as the orchestration the old file header named as its identity, now with nothing else
// in the file.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import splice.core.auth.CredentialKey
import splice.core.failure.EndingHeard
import splice.core.failure.RequestFacts
import splice.core.memory.HeapLease
import splice.core.turn.LiveWatchdogBudget
import splice.head.HeadDeps
import splice.head.hold.ClientWallHeader
import splice.head.hold.ResendLedger
import splice.head.turn.Preparation
import splice.head.turn.SESSION_TAG_CHARS
import splice.head.turn.TurnAccountQuota
import splice.head.turn.TurnDriver
import splice.head.turn.TurnInputs
import splice.head.turn.TurnPreparation
import splice.head.turn.TurnSlot
import splice.head.wire.TurnTrace
import splice.upstream.TurnEnd
import splice.upstream.credentials.Selection

internal class HeadAdmission(
    private val deps: HeadDeps,
    private val admission: AdmissionGate,
    private val telemetry: AdmissionTelemetry,
    private val preparation: TurnPreparation,
    private val responses: AdmissionResponses,
    private val driver: TurnDriver,
) {
    private val keyAccounts = KeyAccount(preparation.provider.auth, deps.quotaBundle)
    private val refusals = PreDriveRefusals(deps, admission, responses, driver, preparation.provider.loginCommand)

    // Failure-handling slice 4: the requests whose turn ended with an answer Claude Code sends again, so the identical
    // request after one is told as its re-send (RequestFacts.repeated) and ends for good if it fails the same way.
    private val resends = ResendLedger(deps.seams.clock)

    fun arrivalTime(): Long = telemetry.arrivalTime()

    suspend fun handleMessages(call: ApplicationCall, arrivalAt: Long = arrivalTime()) {
        if (!admission.acceptingOrRespond(call)) return
        // Fix 3 (Oct 27): before the turn takes a slot or is read, the head's model list follows the credential the
        // turn is sent with, so a turn sent the moment after a sign-in is prepared from that sign-in's list.
        deps.traffic.models.await()
        val perf = telemetry.begin(arrivalAt)
        val t0 = deps.seams.clock()
        val slot = admission.acquireSlotOrRespond(call) ?: return
        telemetry.markAdmitted(perf, t0, slot.heldMs)
        // A detached compaction takes the slot with it (TurnStreamer.driveDetachable): the drive
        // releases it when the upstream turn ends, not this call when its client has gone. The flag
        // is this call's from the start, never a return value: the drive's cancelled-call path
        // THROWS out of serve (review of PR 137), and a finally must not read the flag off a call
        // that never returned.
        val admitted = AdmittedTurn(slot, t0, perf)

        try {
            // Prepared request trees survive decode and may belong to a detached compaction.
            // Their heap lease follows the same slot that already owns that drive's lifetime.
            val owner = object : MaterializationOwner {
                override fun onRelease(release: TurnEnd) {
                    admitted.materializedEnd = release
                }

                override fun leased(lease: HeapLease) {
                    admitted.heapLease = lease
                }
            }
            val leaseStart = telemetry.arrivalTime()
            val prepared = admission.materializeBodyOrRespond(
                call,
                Materializing(owner = owner, beforeRefusal = TurnEnd(admitted::release)),
            ) {
                telemetry.prepare(perf, leaseStart) { preparation.prepareTurn(call, perf) }
            } ?: return
            if (admitted.settle(call, prepared, admission)) serve(call, prepared, admitted)
        } finally {
            admitted.close()
        }
    }

    private suspend fun serve(call: ApplicationCall, prepared: Preparation, admitted: AdmittedTurn) {
        // Only a Ready turn names a live row. settle already returned Local/Replay/Collected ownership,
        // including a borrowed handle whose row still belongs to its independent source.
        when (prepared) {
            is Preparation.Rejected -> responses.respondRefusal(call, prepared.cause)
            is Preparation.Local -> driver.answerLocally(call, prepared, admitted.perf)
            is Preparation.Replay -> {
                // settle returned this retry's candidate before it follows the independently held drive.
                driver.localResponses.replay(call, prepared)
            }
            is Preparation.Collected -> {
                // The collect sibling: this retry waits out its held answer instead of prefilling again.
                driver.localResponses.replayCollected(call, prepared)
            }
            // A non-stream fallback of a streamed compaction: it follows that recording into one body.
            is Preparation.CollectedRecording -> driver.localResponses.collectRecording(call, prepared)
            is Preparation.Ready -> serveReady(call, prepared, admitted)
        }
    }

    private suspend fun serveReady(call: ApplicationCall, prepared: Preparation.Ready, admitted: AdmittedTurn) {
        val meta = prepared.built.meta
        // Before describe: a compaction held for a restart is not yet one the restart's read waits for.
        if (meta.compact && !admission.compactionAdmittedOrRespond(call, TurnEnd(admitted::release))) return
        admitted.slot.describe(
            meta.route.upstreamModel,
            meta.compact,
            meta.scope.sessionId?.take(SESSION_TAG_CHARS),
        )
        // V4-165: the turn ends when its admission slot is released — here on a refusal or
        // an attached drive, inside TurnStreamer for a detached one. One registration
        // covers every exit, because the slot already has to be released on each of them.
        prepared.built.onEnd?.let(admitted.slot::onRelease)
        // V4-319: a streaming turn is listed, and the operator can stop it, from here until
        // the same release ends it. A collect has no open stream a stop could end with a frame.
        admitToLiveTurns(prepared, admitted)

        // V4-134: turn.start fires HERE and nowhere earlier because this is the one path whose every
        // exit writes a perf row — the two local refusals below and the drive all go through
        // TurnTelemetry's emitters, which fire turn.end. Rejected, Local and Replay write no row, so
        // announcing them would leave the console a start with no end.
        deps.seams.events.turnStarted(prepared.built.meta.scope.sessionId)
        // V4-174: the trace begins HERE, before the two local refusals, because a refused turn is a
        // request the head received and answered — a trace that skipped it would show a client
        // retrying for no visible reason. Null for every head whose trace is off.
        val trace = prepared.takeInbound()?.let { deps.stores.captures.trace?.begin(prepared.built.meta, it) }
        admitAccountAndDrive(call, prepared, admitted, trace)
    }

    /** The streaming registration itself: what the operator's live listing watches and can stop,
     *  with the re-anchor policy this provider re-arms on resume. */
    private fun admitToLiveTurns(prepared: Preparation.Ready, admitted: AdmittedTurn) {
        if (!prepared.stream) return
        val meta = prepared.built.meta
        val provider = preparation.provider
        val budget = deps.traffic.watchdog ?: LiveWatchdogBudget { provider.watchdog }
        val resume = WillResume(provider.reanchorPolicy(meta), budget)
        deps.traffic.liveTurns.admitted(admitted.slot, meta, prepared.messagesHash, admitted.perf, resume)
    }

    /** Account selection through its two refusals, then the drive: the tail of [serveReady] as one named step, since
     *  describe/registration/driver each own theirs. No turn waits here on a hold another turn's rate limit armed: it
     *  is sent, and waits only on its own failure (Marcos, Oct 26). */
    private suspend fun admitAccountAndDrive(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
    ) {
        // The key is the account: a head with no login pool records WHICH key sent the request, and a pooled one
        // answers None without a resolve.
        val keyAccount = keyAccounts.naming()
        if (refusals.refuseIfOverBudget(call, prepared, admitted, trace, keyAccount)) return
        // One read of the pool decides both: whether this caller may hold a login of its own, and which login.
        val pool = deps.quotaBundle.activePool
        if (refusals.refuseIfUnproven(call, prepared, admitted, trace, pool)) return
        val callerKey = prepared.built.extraHeaders.takeIf { deps.policy.forwardClientAuth }
            ?.let(CredentialKey::fromHeaders)
        val selection = admitted.perf.timed(splice.core.perf.PerfKeys.ACCOUNT_CHECKS_MS) {
            pool?.awaitingEvidence?.select(
                prepared.built.meta.scope.sessionId,
                excluded = emptySet(),
                callerCredentialKey = callerKey,
            )
        }
        val account = when (selection) {
            null -> null
            is Selection.Chosen -> selection.account
            is Selection.Exhausted -> {
                refusals.refuseNoLogin(call, prepared, admitted, selection, trace)
                return
            }
        }
        try {
            driveReady(call, prepared, admitted, trace, running(prepared, account, keyAccount))
        } finally {
            if (!admitted.wasHandedOff()) account?.releaseCredentialProbe()
        }
    }

    /** The login this turn runs on: the account admission chose, its quota, and the key to record when no account
     *  was proved. Assembled here so the drive is handed one value rather than three. */
    private fun running(
        prepared: Preparation.Ready,
        account: splice.upstream.credentials.AccountSelection?,
        keyAccount: KeyNaming,
    ) = TurnAccountQuota(
        account = account,
        quota = deps.turnQuota.forSession(prepared.built.meta.scope.sessionId, account),
        keyNaming = keyAccount,
    )

    private suspend fun driveReady(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        trace: TurnTrace?,
        running: TurnAccountQuota,
    ) {
        val sender = running.account?.account?.auth ?: preparation.provider.auth
        if (refusals.refuseIfNodeWordUnheld(call, prepared, admitted, trace, sender)) return
        val oversized = admitted.perf.timed(splice.core.perf.PerfKeys.SIZE_PREFLIGHT_MS) {
            refusals.refuseIfOversized(call, prepared, admitted, trace)
        }
        if (oversized) return
        admitted.retainRequest()
        val scope = prepared.built.meta.scope
        val facts = RequestFacts(
            compaction = prepared.built.meta.compact,
            firstExchange = !prepared.hasPriorExchange,
            repeated = resends.repeated(scope.sessionId, scope.messagesHash),
            otherLogin = (deps.quotaBundle.activePool?.members?.size ?: 0) > 1,
        )
        // A hold handed to the client (family L) is not an ending to repeat: its re-send holds anew (slice 5).
        val heard = EndingHeard { answer ->
            if (answer.clientResends && !answer.handedOver) resends.endedForResend(scope.sessionId, scope.messagesHash)
        }
        val inputs = TurnInputs(
            prepared.built,
            TurnSlot(
                admitted.slot,
                clientWallMs = ClientWallHeader.ms(call),
                heapLease = admitted.heapLease,
            ) { admitted.markHandedOff() },
            admitted.t0,
            admitted.perf,
            trace = trace,
            accountQuota = running,
            voice = driver.voice.turn(facts, heard),
        )
        if (prepared.stream) driver.stream(call, inputs) else driver.collect(call, inputs)
    }
}
