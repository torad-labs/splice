// NEW: all-account exhaustion keeps the provider reset in telemetry and bounds only the client retry deadline.
package splice.head.admission

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import splice.core.perf.OutcomeTag
import splice.core.util.WallClock
import splice.head.HeadDeps
import splice.head.turn.Preparation
import splice.head.turn.TurnDriver
import splice.head.wire.TurnTrace
import splice.upstream.credentials.AccountResetText
import splice.upstream.credentials.Selection
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS

/** A quota refusal is not a native provider reply. Preserve its existing bounded deadline and rejected quota headers. */
internal class ExhaustedAccountAdmission(
    private val deps: HeadDeps,
    private val responses: AdmissionResponses,
    private val driver: TurnDriver,
    private val wallClock: WallClock,
) {
    suspend fun refuse(
        call: ApplicationCall,
        prepared: Preparation.Ready,
        admitted: AdmittedTurn,
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
        val retryEpochSeconds = exhausted.earliestResetEpochSeconds?.let {
            val hold = AccountResetText.normalizedInstant(it).toEpochMilli() - now
            (now + hold.coerceIn(0L, MAX_RATE_LIMIT_COOLDOWN_MS)) / MILLIS_PER_SECOND
        }
        deps.turnQuota.forSession(prepared.built.meta.sessionId, null)?.clientHeadersRejected(retryEpochSeconds)
            ?.forEach { (name, value) -> call.response.header(name, value) }
        admitted.close()
        responses.respondRateLimited(call, exhausted.message, retryEpochSeconds)
    }
}
