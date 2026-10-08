// NEW: auth-missing / upstream-failed endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.head.turn

import splice.core.perf.OutcomeTag
import splice.core.turn.ErrorType
import splice.core.usage.PlanLimit
import splice.core.util.ERR_SNIPPET
import splice.core.util.LogSink
import splice.head.HeadHealthCounters
import splice.head.admission.TurnQuota
import splice.head.pipeline.FailureRenderer
import splice.upstream.Provider
import splice.upstream.failure.FailureSource
import splice.upstream.failure.ForeignCredential
import splice.upstream.failure.UpstreamFailureClassifier
import splice.upstream.transport.UpstreamAuthMissing
import splice.upstream.transport.UpstreamFailed

internal class TurnKnownEnd(
    private val provider: Provider,
    private val log: LogSink,
    private val telemetry: TurnTelemetry,
    private val failures: TurnFailures,
    private val health: HeadHealthCounters,
    private val quotas: TurnQuota? = null,
) {

    // V4-59: named for the renderer, not the TurnFailures above — the classified message reaching
    // emitError is the SECOND place a vendor's raw body could become client text. The classifier
    // lifts error.message out of a JSON body, but falls back to the WHOLE body whenever the shape
    // is one it cannot read (our own detail-only fail-fast is exactly that shape), and this arm
    // then emitted the fallback verbatim.
    private val renderer = FailureRenderer()

    /** True when [e] is a known upstream-auth or upstream-HTTP failure this surface owns. */
    suspend fun tryEmit(drive: TurnDrive, e: Throwable): Boolean = when (e) {
        is UpstreamAuthMissing -> {
            log(telemetry.errTurn("auth-missing", drive, ": ${e.message}"))
            // DR-128: account BEFORE the emit — a dead-client write makes emitError rethrow after
            // sealing, and the turn must not vanish from the perf JSONL and G20 counters (the
            // 2026-07-19 storm shape: dead clients + failing upstream). Same law on every surface.
            telemetry.recordPerf(drive, OutcomeTag.AUTH_MISSING.wire)
            health.local() // no upstream call ever happened: missing local credentials
            drive.emitter.emitError(
                ErrorType.AUTHENTICATION,
                "${provider.key}: no upstream credentials${failures.loginHint()}",
            )
            true
        }
        is UpstreamFailed -> {
            // V4-242: a 401 naming a credential the account did not send is the upstream's failure, never
            // the user's sign-in (ForeignCredential); the credential it compares is the one sent.
            val sent = (drive.account?.account?.auth ?: provider.auth).credentials()
            val read = UpstreamFailureClassifier.classify(FailureSource.HTTP, e.body, e.status)
            val failure = ForeignCredential.upstreamsOwn(read, e.body, sent)
            val detail = "type=${failure.type.wireName} status=${e.status} msg=${failure.message.take(ERR_SNIPPET)}"
            val outcome = endingTag(drive, e.planLimit)
            log(telemetry.errTurn(outcome.wire, drive, detail))
            // V4-59: the code rides OUTSIDE the snippet bound on purpose. Bounding the presented
            // line instead pushed the appended login hint past ERR_SNIPPET, silently dropping the
            // one part of this message that tells the operator what to DO — caught by the existing
            // login-hint test, which is exactly what it is for.
            val classified = renderer.present(failure.type, failure.message)
            val boundedMessage = "[${classified.code}] ${classified.body.take(ERR_SNIPPET)}"
            val standby = if (failure.type == ErrorType.RATE_LIMIT) quotas?.standbyRefusal(drive.account) else null
            val message = message(failure.type, boundedMessage, standby)
            // The emitter first decides the pending HTTP status. Record the resulting trace and
            // perf row in finally, so a dead-client write still counts the exact failure, its cause,
            // and the retry loop's attempt count. The pre-commit 400 must not leave a 200 trace.
            accountFailure(e, failure.type)
            e.rateLimitReply?.let { reply -> drive.rateLimitRelay?.relay(quotas?.withStandby(reply, standby) ?: reply) }
            // V4-81: translated failures retain the emitter's wire type and permanence.
            // Native 429s choose HTTP refusal above while status remains uncommitted; classified
            // context overflow before client content can still choose HTTP 400.
            try {
                drive.emitter.emitError(failure.type, message, permanent = !failure.transient)
            } finally {
                drive.markPermanent(!failure.transient)
                telemetry.recordPerf(
                    drive,
                    outcome.wire,
                    failure.type == ErrorType.RATE_LIMIT,
                    cause = failure.cause.name,
                    layers = e.layers,
                )
            }
            true
        }
        else -> false
    }

    private fun message(type: ErrorType, bounded: String, standby: String?): String =
        if (type == ErrorType.AUTHENTICATION && provider.loginCommand.isNotEmpty()) {
            "$bounded; run: ${provider.loginCommand}"
        } else {
            bounded + standby?.let { " $it" }.orEmpty()
        }

    private fun accountFailure(error: UpstreamFailed, type: ErrorType) {
        if (error.localHold) health.local() else health.provider()
        if (error.planLimit == null && type == ErrorType.RATE_LIMIT) {
            if (error.localHold) health.cooldownHeld() else health.rateLimited()
        }
    }

    /** V4-419: the tag an upstream failure ends the turn on. One caused by a spent plan window ends
     *  [OutcomeTag.PLAN_LIMIT] and speaks the window and its reset on the trace before the record closes (a surface
     *  that spoke first wins over the table's sentence); every other one is [OutcomeTag.UPSTREAM_FAILED]. */
    private fun endingTag(drive: TurnDrive, plan: PlanLimit?): OutcomeTag {
        if (plan == null) return OutcomeTag.UPSTREAM_FAILED
        drive.trace?.failureSentence(OutcomeSentences.planLimit(plan))
        return OutcomeTag.PLAN_LIMIT
    }
}
