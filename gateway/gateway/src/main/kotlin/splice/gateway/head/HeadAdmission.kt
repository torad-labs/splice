// PORT-OF: splice/gateway/head/HeadServer.kt (handleMessages) @ 1caedd6 — invariants unchanged:
// admission is acquired BEFORE the body is read, so a queued call retains no transcript and only
// an admitted call may enter the materialization gate; the slot is released in a NonCancellable
// finally (leak-safe teardown); a body-parse failure is a client 400, never a crash. Split out
// (HD-24) as the orchestration the old file header named as its identity, now with nothing else
// in the file.
package splice.gateway.head

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.perf.TurnPerf
import splice.core.util.WallClock
import splice.spi.AccountResetText
import splice.spi.AllAccountsExhausted
import splice.spi.InflightGate
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
        val t0 = deps.clock()
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
            withContext(NonCancellable) { if (!admitted.handedOff.get()) admitted.slot.release() }
        }
    }

    /** What one admitted call holds: its gate slot, its start, its perf row, and the flag a
     *  detached drive flips when it takes the slot with it. */
    private data class Admitted(
        val slot: InflightGate.Slot,
        val t0: Long,
        val perf: TurnPerf,
        val handedOff: AtomicBoolean = AtomicBoolean(false),
    )

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
            is Preparation.Ready -> serveReady(call, prepared, admitted)
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
     *  THE DEADLINE IS THE PROVIDER'S, NEVER THE GATEWAY'S. [UpstreamClient.rateLimitedForMs] is
     *  splice's own follower protection, clamped to 120s; telling a client to return then, when the
     *  provider said 88 minutes, just buys another 429. Retry-After carries the provider reset or
     *  nothing at all — an absent header leaves the client its own backoff, which is strictly better
     *  than a confident wrong number.
     *
     *  NEVER-BELOW-STATUS-QUO: nothing armed, nothing changes — the turn takes the identical path it
     *  did before. A turn that is ALREADY streaming when the limit lands still ends in an error
     *  frame, because its 200 is genuinely spent by then; that is today's behaviour and out of scope
     *  here. */
    private suspend fun refuseIfRateLimited(call: ApplicationCall): Boolean {
        val armedMs = deps.upstream.rateLimitedForMs
        if (armedMs <= 0L) return false
        val providerResetMs = deps.upstream.providerResetForMs
        val resetEpochSeconds =
            providerResetMs.takeIf { it > 0L }?.let { (wallClock() + it) / MILLIS_PER_SECOND }
        // V4-51's seam: the refusal states `rejected` and carries the plain
        // anthropic-ratelimit-unified-reset, which is the member Claude Code reads off a 429 to
        // decide when to come back. Without this the same response would assert `allowed` while
        // refusing the turn — splice contradicting itself in two headers of the same reply.
        deps.quota?.clientHeadersRejected(resetEpochSeconds)?.forEach { (name, value) ->
            call.response.header(name, value)
        }
        responses.respondRateLimited(call, rateLimitedMessage(armedMs, resetEpochSeconds), resetEpochSeconds)
        return true
    }

    /** Names BOTH horizons, because they are different facts and the operator needs both: when the
     *  provider says the quota returns, and how long this gateway is holding its own retries. A
     *  message that reported only the gateway's 120s cooldown read as "back in two minutes" against
     *  an 88-minute reset. */
    private fun rateLimitedMessage(armedMs: Long, resetEpochSeconds: Long?): String {
        val holding = "this gateway is holding retries for " +
            "${(armedMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND}s to avoid a retry wave"
        if (resetEpochSeconds == null) {
            return "Rate limit exceeded — the upstream named no reset time, so $holding."
        }
        return "Rate limit exceeded until ${AccountResetText.format(resetEpochSeconds)} " +
            "(the upstream's own reset); $holding."
    }

    private suspend fun serveReady(call: ApplicationCall, prepared: Preparation.Ready, admitted: Admitted) {
        if (refuseIfRateLimited(call)) return
        val account = try {
            deps.accountPool?.select(prepared.built.meta.sessionId)
        } catch (e: AllAccountsExhausted) {
            driver.recordAccountExhausted(
                prepared.built.meta,
                admitted.perf,
                admitted.t0,
                e.earliestResetEpochSeconds,
            )
            responses.respondRateLimited(call, e.message.orEmpty(), e.earliestResetEpochSeconds)
            return
        }
        try {
            val inputs = TurnInputs(
                prepared.built,
                admitted.slot,
                admitted.t0,
                admitted.perf,
                slotHandedOff = admitted.handedOff,
                account = account,
                quota = account?.account?.label?.let(deps.accountQuotas::get) ?: deps.quota,
            )
            // stream:true → SSE (the interactive path); stream:false → one buffered JSON body
            // (Claude Code's internal non-stream calls, served by collecting the same machinery).
            if (prepared.stream) driver.stream(call, inputs) else driver.collect(call, inputs)
        } finally {
            if (!admitted.handedOff.get()) account?.releaseCredentialProbe()
        }
    }
}

private const val MILLIS_PER_SECOND = 1000L
