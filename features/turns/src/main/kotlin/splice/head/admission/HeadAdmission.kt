// PORT-OF: splice/gateway/head/HeadServer.kt (handleMessages) @ 1caedd6 — invariants unchanged:
// admission is acquired BEFORE the body is read, so a queued call retains no transcript and only
// an admitted call may enter the materialization gate; the slot is released in a NonCancellable
// finally (leak-safe teardown); a body-parse failure is a client 400, never a crash. Split out
// (HD-24) as the orchestration the old file header named as its identity, now with nothing else
// in the file.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.perf.OutcomeTag
import splice.core.perf.TurnPerf
import splice.core.util.WallClock
import splice.head.ClientAuth
import splice.head.HeadDeps
import splice.head.turn.Preparation
import splice.head.turn.TurnDriver
import splice.head.turn.TurnInputs
import splice.head.turn.TurnPreparation
import splice.head.wire.TurnTrace
import splice.upstream.credentials.AccountResetText
import splice.upstream.credentials.Selection
import splice.upstream.retry.InflightGate
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import java.util.concurrent.atomic.AtomicBoolean

internal class HeadAdmission(
    private val deps: HeadDeps,
    private val clientAuth: ClientAuth,
    private val admission: AdmissionGate,
    private val telemetry: AdmissionTelemetry,
    private val preparation: TurnPreparation,
    private val responses: AdmissionResponses,
    private val driver: TurnDriver,
    /** V4-50 reads a WALL instant because a reset is a calendar fact the client must be told in
     *  epoch seconds; [HeadDeps.clock] is an ElapsedClock and cannot answer that. Defaulted so no
     *  construction site changes, injectable so the refusal is testable without sleeping. */
    private val wallClock: WallClock = WallClock(System::currentTimeMillis),
) {
    suspend fun handleMessages(call: ApplicationCall) {
        if (!clientAuth.authorize(call) || !admission.acceptingOrRespond(call)) return
        val perf = telemetry.begin()
        val t0 = deps.seams.clock()
        val slot = admission.acquireSlotOrRespond(call) ?: return
        telemetry.markAdmitted(perf)
        // A detached compaction takes the slot with it (TurnStreamer.driveDetachable): the drive
        // releases it when the upstream turn ends, not this call when its client has gone. The flag
        // is this call's from the start, never a return value: the drive's cancelled-call path
        // THROWS out of serve (review of PR 137), and a finally must not read the flag off a call
        // that never returned.
        val admitted = Admitted(slot, t0, perf)

        try {
            val prepared = admission.materializeOrRespond(call) { preparation.prepareTurn(call, perf) } ?: return
            serve(call, prepared, admitted)
        } finally {
            withContext(NonCancellable) { if (!admitted.wasHandedOff()) admitted.slot.release() }
        }
    }

    /** What one admitted call holds: its gate slot, its start, its perf row — and the hand-off flag
     *  it OWNS.
     *
     *  A PLAIN class, deliberately, not a `data class` (kt-no-atomic-in-data-class). The rule names
     *  a real harm and that harm is value semantics: `equals`/`hashCode` would compare the handle by
     *  reference and `copy()` would either share the flag with a second Admitted or silently drop
     *  it — two admits that compare equal while holding ONE slot. Neither could happen here (nothing
     *  copies or compares an Admitted), and the rule's own note names the owner's shape: whoever
     *  calls `set` gives it a private atomic and a named method, and the bundle gets a port onto
     *  that method. So `handedOff` is private, `markHandedOff()`/`wasHandedOff()` are the two names
     *  it is allowed to be reached by, and the `data` contract — never used, and only able to
     *  mislead — is gone. This is a per-call lifecycle owner, which is what it always was. */
    private class Admitted(
        val slot: InflightGate.Slot,
        val t0: Long,
        val perf: TurnPerf,
    ) {
        private val handedOff = AtomicBoolean(false)

        /** The drive took the slot with it: the admission's finally must now leave it alone. */
        fun markHandedOff() {
            handedOff.set(true)
        }

        fun wasHandedOff(): Boolean = handedOff.get()
    }

    private suspend fun serve(call: ApplicationCall, prepared: Preparation, admitted: Admitted) {
        when (prepared) {
            is Preparation.Rejected -> responses.respondInvalidRequest(call, prepared.message)
            is Preparation.Local -> driver.answerLocally(call, prepared)
            is Preparation.Replay -> {
                // A retry following a compaction still in flight is not an admission: the drive it
                // follows holds a slot already (TurnStreamer.driveDetachable), so this one goes
                // back before the wait — else one compaction counts twice against the gate for
                // however long the upstream turn still runs (review of PR 137). Release is
                // idempotent (InflightGate.Slot), so the finally above stays a no-op for it.
                if (!prepared.recording.isComplete) admitted.slot.release()
                driver.replay(call, prepared)
            }
            is Preparation.Ready -> {
                // V4-165: the turn ends when its admission slot is released — here on a refusal or
                // an attached drive, inside TurnStreamer for a detached one. One registration
                // covers every exit, because the slot already has to be released on each of them.
                prepared.built.onEnd?.let(admitted.slot::onRelease)
                serveReady(call, prepared, admitted)
            }
        }
    }

    /** V4-50: A RATE-LIMITED TURN IS REFUSED HERE, BEFORE A RESPONSE IS COMMITTED — which is the
     *  whole point, and why this could never be fixed by rewording anything.
     *
     *  The armed cooldown has always been discovered deep inside the drive
     *  (UpstreamClient.post -> failFastIfArmed). By then TurnStreamer has called respondTextWriter,
     *  the 200 and the SSE headers are on the wire, and the only refusal still expressible is an
     *  `event: error` frame. Claude Code's retry-until-reset fires on an APIError with status 429
     *  and reads the reset off THAT response's headers; a frame inside a 200 is not an APIError, so
     *  none of it runs and the turn simply dies. Three separate operator reports in one day were all
     *  this, and all three were mistaken for a wording problem.
     *
     *  So the check moves UP to admission, ahead of the drive, where a status line is still ours to
     *  write — and answers through the SAME [AdmissionResponses.respondRateLimited] the pooled
     *  AllAccountsExhausted path below has always used. This is an UNGATING, not a new terminal:
     *  that shape is proven on the pooled path, and a single-account head could simply never reach
     *  it. The same pooled/unpooled split disabled captureProviderReset (V4-47) and markUnavailable,
     *  which is the actual defect class here — a head with one account took every penalty of the
     *  cooldown and was denied every recovery path it had.
     *
     *  THE DEADLINE IS WHEN THIS GATEWAY NEXT LETS A REQUEST THROUGH — V4-61 reversed V4-50's
     *  first choice, on evidence. V4-50 sent the provider's reset; muse stamps its 5h-WINDOW reset
     *  on a burst 429 (the live episode said 88 minutes) while the operator's own re-send moments
     *  later succeeded. A client told to sleep 88 minutes for a limit that clears in seconds is the
     *  worse failure. So Retry-After and the plain unified-reset carry the cooldown lift, at most
     *  120s: by the time a turn is refused here, splice has already retried upstream on the 15s
     *  schedule and armed on exhaustion, and if the window really is spent the re-probe after the
     *  lift meets another 429 and re-arms — a bounded poll, never a blind 88-minute sleep. The
     *  provider's window still rides in the message and the telemetry as information, and in
     *  -5h-reset via quota.
     *
     *  NEVER-BELOW-STATUS-QUO: nothing armed, nothing changes — the turn takes the identical path it
     *  did before. A turn that is ALREADY streaming when the limit lands still ends in an error
     *  frame, because its 200 is genuinely spent by then; that is today's behaviour and out of scope
     *  here. */
    private suspend fun refuseIfRateLimited(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: Admitted,
        trace: TurnTrace?,
    ): Boolean {
        val armedMs = deps.upstream.rateLimitedForMs
        if (armedMs <= 0L) return false
        val now = wallClock()
        val retryEpochSeconds = clientRetryEpochSeconds(now, armedMs)
        val windowResetEpochSeconds =
            deps.upstream.providerResetForMs.takeIf { it > 0L }?.let { (now + it) / MILLIS_PER_SECOND }
        // V4-51's seam: the refusal states `rejected` and carries the plain
        // anthropic-ratelimit-unified-reset, which is the member Claude Code reads off a 429 to
        // decide when to come back. Without this the same response would assert `allowed` while
        // refusing the turn — splice contradicting itself in two headers of the same reply.
        // V4-84 (4): the SELECTED account's tracker, not the primary's, even though this arm runs
        // BEFORE selection — a session sticky to account B is still routed to B on its next turn.
        // TurnQuota.forSession is the one resolver for exactly this precedence (V4-99).
        deps.turnQuota.forSession(prepared.built.meta.sessionId, null)?.clientHeadersRejected(retryEpochSeconds)
            ?.forEach { (name, value) -> call.response.header(name, value) }
        // V4-55: recorded BEFORE responding, mirroring the pooled sibling below. A refusal that
        // leaves no perf row and no journal line is a turn that, from splice's own telemetry, never
        // happened — which is how three reports of this exact failure went unfalsifiable in a day.
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(
                OutcomeTag.RATE_LIMITED.wire,
                "provider_reset=${AccountResetText.format(windowResetEpochSeconds)} gateway_hold=${armedMs}ms",
                trace,
            ),
        )
        responses.respondRateLimited(call, rateLimitedMessage(armedMs, windowResetEpochSeconds), retryEpochSeconds)
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
        admitted: Admitted,
        trace: TurnTrace?,
    ): Boolean {
        val block = deps.quotaBundle.budget.admit() ?: return false
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(OutcomeTag.BUDGET_BLOCKED.wire, block.detail, trace),
        )
        responses.respondBudgetBlocked(call, block.message)
        return true
    }

    /** A sentence, in the order a person needs it: what happened, that splice already tried, when
     *  to retry — and the provider's window as information, never as the instruction. Naming both
     *  horizons matters because they are different facts: a message carrying only the 120s hold
     *  read as "back in two minutes" against an 88-minute window, and one carrying only the window
     *  told the operator to wait 88 minutes for a limit his own re-send cleared in seconds. */
    private fun rateLimitedMessage(armedMs: Long, windowResetEpochSeconds: Long?): String {
        val waitS = (armedMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
        val base = "Rate limit exceeded. This gateway already retried upstream and is still being " +
            "limited, so it is holding new turns for ${waitS}s — retry after that."
        if (windowResetEpochSeconds == null) return base
        return "$base The upstream reports its quota window resets at " +
            "${AccountResetText.format(windowResetEpochSeconds)}; if this keeps happening, that is the real deadline."
    }

    /** V4-61'S LAW IN ONE PLACE, because it was written once and forgotten on the sibling branch
     *  (V4-77): the client's deadline is a HOLD FROM NOW — when this gateway next lets a request
     *  through — never a provider instant, and never past [MAX_RATE_LIMIT_COOLDOWN_MS]. Both refusals in
     *  this file compute it here so neither can drift from the other again. The armed-cooldown
     *  caller is already inside the clamp (RateLimitCooldown arms at most its own ceiling), so the
     *  coerce is a wall for it and the actual bound for [refuseExhausted].
     *
     *  WHY A HOLD AND NOT THE REAL RESET: a non-persistent Claude Code ABORTS the turn on a
     *  Retry-After past 60s and a persistent one sleeps through it, so a 3-day pooled reset on the
     *  wire is the turn dying either way. The real reset is not lost — it rides in the refusal
     *  message and in the perf row — and the client that comes back at the bound meets a re-probe
     *  that either serves it or re-refuses with a fresh bounded deadline. */
    private fun clientRetryEpochSeconds(now: Long, holdMs: Long): Long =
        (now + holdMs.coerceIn(0L, MAX_RATE_LIMIT_COOLDOWN_MS)) / MILLIS_PER_SECOND

    /** V4-77: the POOLED twin of [refuseIfRateLimited] — every account is blocked, so no turn can
     *  start, and the client is told so with the SAME bounded deadline a cooldown refusal gives.
     *  Before this it was handed [AllAccountsExhausted.earliestResetEpochSeconds] raw, which is the
     *  quota `resetsAt` / provider unavailability bounded only by seven days.
     *
     *  The provider's own reset is UNTOUCHED in the two places it belongs: the exception's message
     *  (AccountResetText.exhausted names the instant) and the perf/journal row, which still records
     *  the raw epoch. Only the wire deadline is bounded. An UNKNOWN reset still ships no
     *  Retry-After at all — inventing one is a claim this refusal cannot support, and a pinned
     *  behaviour.
     *
     *  V4-80: and it states `rejected` on V4-51's seam, the same one [refuseIfRateLimited] uses.
     *  Before this the pooled refusal emitted NO quota headers, so a pooled head with a tracker
     *  answered `anthropic-ratelimit-unified-status: allowed` on the very response refusing the
     *  turn — the identical self-contradiction V4-51 fixed for the cooldown branch, surviving on
     *  the branch V4-51 did not open. The reset member carries the CLIENT deadline (the bounded
     *  [retryEpochSeconds]), never the provider window, so the plain unified-reset and Retry-After
     *  name the same instant; a null deadline states the refusal and omits the member. */
    private suspend fun refuseExhausted(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: Admitted,
        exhausted: Selection.Exhausted,
        trace: TurnTrace?,
    ) {
        driver.recordLocalRefusal(
            prepared.built.meta,
            admitted.perf,
            admitted.t0,
            LocalRefusal(
                OutcomeTag.ALL_ACCOUNTS_EXHAUSTED.wire,
                "earliest_reset=${AccountResetText.format(exhausted.earliestResetEpochSeconds)}",
                trace,
            ),
        )
        val now = wallClock()
        // normalizedInstant is the same four-digit-year clamp AdmissionResponses formats through,
        // borrowed here so an absurd upstream reset cannot overflow the subtraction before the
        // hold is bounded.
        val retryEpochSeconds = exhausted.earliestResetEpochSeconds?.let {
            clientRetryEpochSeconds(now, AccountResetText.normalizedInstant(it).toEpochMilli() - now)
        }
        // V4-84 (4): the SELECTED account's tracker, not the primary's — on a pooled head whose
        // session is sticky to another account this 429 used to ship the OTHER account's bars.
        // TurnQuota.forSession is the one resolver for exactly this precedence (V4-99).
        deps.turnQuota.forSession(prepared.built.meta.sessionId, null)?.clientHeadersRejected(retryEpochSeconds)
            ?.forEach { (name, value) -> call.response.header(name, value) }
        responses.respondRateLimited(call, exhausted.message, retryEpochSeconds)
    }

    private suspend fun serveReady(call: ApplicationCall, prepared: Preparation.Ready, admitted: Admitted) {
        // V4-134: turn.start fires HERE and nowhere earlier because this is the one path whose every
        // exit writes a perf row — the two local refusals below and the drive all go through
        // TurnTelemetry's emitters, which fire turn.end. Rejected, Local and Replay write no row, so
        // announcing them would leave the console a start with no end.
        deps.seams.events.turnStarted(prepared.built.meta.sessionId)
        // V4-174: the trace begins HERE, before the two local refusals, because a refused turn is a
        // request the head received and answered — a trace that skipped it would show a client
        // retrying for no visible reason. Null for every head whose trace is off.
        val trace = prepared.inbound?.let { deps.stores.trace?.begin(prepared.built.meta, it) }
        if (refuseIfOverBudget(call, prepared, admitted, trace)) return
        if (refuseIfRateLimited(call, prepared, admitted, trace)) return
        val account = when (val selection = deps.quotaBundle.accountPool?.select(prepared.built.meta.sessionId)) {
            null -> null
            is Selection.Chosen -> selection.account
            is Selection.Exhausted -> {
                refuseExhausted(call, prepared, admitted, selection, trace)
                return
            }
        }
        try {
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
            // stream:true → SSE (the interactive path); stream:false → one buffered JSON body
            // (Claude Code's internal non-stream calls, served by collecting the same machinery).
            if (prepared.stream) driver.stream(call, inputs) else driver.collect(call, inputs)
        } finally {
            if (!admitted.wasHandedOff()) account?.releaseCredentialProbe()
        }
    }
}

private const val MILLIS_PER_SECOND = 1000L
