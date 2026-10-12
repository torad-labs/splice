// NEW: all-account exhaustion keeps the provider reset in telemetry and tells the client to recheck soon.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.util.WallClock
import splice.head.HeadDeps
import splice.head.turn.Preparation
import splice.head.turn.TurnDriver
import splice.head.wire.TurnTrace
import splice.upstream.credentials.AccountResetText
import splice.upstream.credentials.Selection

/** A quota refusal, not a native provider reply: its client deadline is the recheck, its quota headers rejected. */
internal class ExhaustedAccountAdmission(
    private val deps: HeadDeps,
    private val responses: AdmissionResponses,
    private val driver: TurnDriver,
    wallClock: WallClock,
) {
    private val recheck = ClientRecheck(wallClock)

    suspend fun refuse(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
        exhausted: Selection.Exhausted,
        trace: TurnTrace?,
    ) {
        exhausted.earliestResetEpochSeconds?.let { reset ->
            admitted.perf.setCount(PerfKeys.EARLIEST_RESET_EPOCH_SECONDS, reset)
        }
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
        // The message names the real reset; the client is told to come back within the recheck, to a pool that may
        // have gained a login by then.
        val retryEpochSeconds = recheck.deadline(
            exhausted.earliestResetEpochSeconds?.let { AccountResetText.normalizedInstant(it).epochSecond },
        )
        deps.turnQuota.forSession(prepared.built.meta.scope.sessionId, null)?.clientHeadersRejected(retryEpochSeconds)
            ?.forEach { (name, value) -> call.response.header(name, value) }
        admitted.close()
        val standby = deps.turnQuota.standbyRefusal(null)
        val message = exhausted.message + standby?.let { " $it" }.orEmpty()
        responses.respondRateLimited(call, message, retryEpochSeconds)
    }
}
