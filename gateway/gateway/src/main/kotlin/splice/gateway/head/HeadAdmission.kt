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
        // releases it when the upstream turn ends, not this call when its client has gone.
        var handedOff: AtomicBoolean? = null

        try {
            val prepared = admission.materializeOrRespond(call) { preparation.prepareTurn(call, perf) } ?: return
            handedOff = serve(call, prepared, slot, t0, perf)
        } finally {
            withContext(NonCancellable) { if (handedOff?.get() != true) slot.release() }
        }
    }

    /** Returns the drive's slot-handoff flag for a driven turn; null for the answers that need none. */
    private suspend fun serve(
        call: ApplicationCall,
        prepared: Preparation,
        slot: InflightGate.Slot,
        t0: Long,
        perf: TurnPerf,
    ): AtomicBoolean? = when (prepared) {
        is Preparation.Rejected -> null.also { responses.respondInvalidRequest(call, prepared.message) }
        is Preparation.Local -> null.also { driver.answerLocally(call, prepared) }
        is Preparation.Replay -> null.also { driver.replay(call, prepared) }
        is Preparation.Ready -> {
            val inputs = TurnInputs(prepared.built, slot, t0, perf)
            // stream:true → SSE (the interactive path); stream:false → one buffered JSON body
            // (Claude Code's internal non-stream calls, served by collecting the same machinery).
            if (prepared.stream) driver.stream(call, inputs) else driver.collect(call, inputs)
            inputs.slotHandedOff
        }
    }
}
