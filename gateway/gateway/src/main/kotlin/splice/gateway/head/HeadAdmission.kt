// PORT-OF: splice/gateway/head/HeadServer.kt (handleMessages) @ 1caedd6 — invariants unchanged:
// admission is acquired BEFORE the body is read, so a queued call retains no transcript and only
// an admitted call may enter the materialization gate; the slot is released in a NonCancellable
// finally (leak-safe teardown); a body-parse failure is a client 400, never a crash. Split out
// (HD-24) as the orchestration the old file header named as its identity, now with nothing else
// in the file.
package splice.gateway.head

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.perf.TurnPerf
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
            is Preparation.Ready -> {
                val inputs = TurnInputs(
                    prepared.built,
                    admitted.slot,
                    admitted.t0,
                    admitted.perf,
                    slotHandedOff = admitted.handedOff,
                )
                // stream:true → SSE (the interactive path); stream:false → one buffered JSON body
                // (Claude Code's internal non-stream calls, served by collecting the same machinery).
                if (prepared.stream) driver.stream(call, inputs) else driver.collect(call, inputs)
            }
        }
    }
}
