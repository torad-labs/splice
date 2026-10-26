// NEW: auth-missing / upstream-failed endings, split from TurnEnding
// (concentration, 2026-08-19) so emitFailure is not billed for this surface. Same-package.
package splice.head.turn

import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.turn.ErrorType
import splice.core.util.ERR_SNIPPET
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.head.HeadHealthCounters
import splice.head.admission.TurnQuota
import splice.head.perf.PerfFailure
import splice.head.pipeline.FailureRenderer
import splice.upstream.Provider
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

    /** The local credentials were missing: no upstream call ever happened. */
    suspend fun emitAuthMissing(drive: TurnDrive) {
        log(telemetry.errTurn("auth-missing", drive, ": no upstream credentials"))
        // DR-128: account BEFORE the emit — a dead-client write makes emitError rethrow after
        // sealing, and the turn must not vanish from the perf JSONL and G20 counters (the
        // 2026-07-19 storm shape: dead clients + failing upstream). Same law on every surface.
        telemetry.recordPerf(drive, OutcomeTag.AUTH_MISSING.wire)
        health.local() // no upstream call ever happened: missing local credentials
        drive.emitter.emitError(
            FailureCause.Splice.NoLogin(provider.loginCommand.ifEmpty { null }),
            "${provider.key}: no upstream credentials${failures.loginHint()}",
        )
    }

    /** The upstream host's own refusal after retries. Its cause was read once, where the retry loop gave up; V4-242's
     *  rule is in that reading: a 401 naming a credential the failing attempt did not send is the upstream's own
     *  failure, never the user's sign-in (ForeignCredential). */
    suspend fun emitFailed(drive: TurnDrive, e: UpstreamFailed) {
        val cause = e.cause
        val type = ClientWire.ownType(cause)
        val status = e.evidence.status
        val detail = "type=${type.wireName} status=$status msg=${e.evidence.message.take(ERR_SNIPPET)}"
        val outcome = endingTag(drive, e)
        log(telemetry.errTurn(outcome.wire, drive, detail))
        // V4-59: the code rides OUTSIDE the snippet bound on purpose. Bounding the presented
        // line instead pushed the appended login hint past ERR_SNIPPET, silently dropping the
        // one part of this message that tells the operator what to DO — caught by the existing
        // login-hint test, which is exactly what it is for.
        val classified = renderer.present(type, e.evidence.message)
        val boundedMessage = "[${classified.code}] ${classified.body.take(ERR_SNIPPET)}"
        val standby = if (type == ErrorType.RATE_LIMIT) quotas?.standbyRefusal(drive.account) else null
        val message = message(type, boundedMessage, standby)
        // The emitter first decides the pending HTTP status. Record the resulting trace and
        // perf row in finally, so a dead-client write still counts the exact failure, its cause,
        // and the retry loop's attempt count. The pre-commit 400 must not leave a 200 trace.
        accountFailure(e, type)
        e.native?.let { reply -> drive.rateLimitRelay?.relay(quotas?.withStandby(reply, standby) ?: reply) }
        // V4-81: the emitter asks ClientWire for the wire type, which keeps a permanent cause's own.
        // Native 429s choose HTTP refusal above while status remains uncommitted; classified
        // context overflow before client content can still choose HTTP 400.
        try {
            drive.emitter.emitError(cause, message)
        } finally {
            drive.markPermanent(cause.permanent)
            status?.let { drive.perf.setCount(PerfKeys.PROVIDER_STATUS, it.toLong()) }
            telemetry.recordPerf(
                drive,
                outcome.wire,
                type == ErrorType.RATE_LIMIT,
                // V4-444: the provider's own words, or nothing: no status means no answer came back, and
                // ForeignCredential judged them from the reading BEFORE its own rewrite, whose clause splice
                // wrote for the CLIENT and would sit wrongly under this label.
                PerfFailure(cause = cause.persisted.name, layers = e.layers, providerMessage = e.evidence.said),
            )
        }
    }

    private fun message(type: ErrorType, bounded: String, standby: String?): String =
        if (type == ErrorType.AUTHENTICATION && provider.loginCommand.isNotEmpty()) {
            "$bounded; run: ${provider.loginCommand}"
        } else {
            bounded + standby?.let { " $it" }.orEmpty()
        }

    private fun accountFailure(error: UpstreamFailed, type: ErrorType) {
        health.provider()
        if (error.planLimit == null && type == ErrorType.RATE_LIMIT) health.rateLimited()
    }

    /** V4-419: the tag an upstream failure ends the turn on. One caused by a spent plan window ends
     *  [OutcomeTag.PLAN_LIMIT] and speaks the window and its reset on the trace before the record closes (a surface
     *  that spoke first wins over the table's sentence); the provider's own 429 relayed to the client is
     *  [OutcomeTag.RATE_LIMIT_RELAYED], a rate limit and not a give-up; any other 429, a burst with no reply to relay
     *  or the hold armed behind one, is [OutcomeTag.RATE_LIMITED], the tag admission writes for the same hold; every
     *  other one is [OutcomeTag.UPSTREAM_FAILED].
     *
     *  V4-444: the reset instant is also COUNTED on the row, so a surface reading the perf file alone (the console's
     *  Requests list) can say when the window comes back instead of only that it was spent. Before this it was
     *  recorded on one ending out of three: the one where admission turned the request away with every account spent
     *  ([splice.head.admission.ExhaustedAccountAdmission]). */
    private fun endingTag(drive: TurnDrive, failed: UpstreamFailed): OutcomeTag {
        val plan = failed.planLimit ?: return when {
            failed.native != null -> OutcomeTag.RATE_LIMIT_RELAYED
            failed.evidence.status == HttpStatus.TOO_MANY_REQUESTS -> OutcomeTag.RATE_LIMITED
            else -> OutcomeTag.UPSTREAM_FAILED
        }
        drive.trace?.failureSentence(OutcomeSentences.planLimit(plan))
        drive.perf.setCount(PerfKeys.EARLIEST_RESET_EPOCH_SECONDS, plan.resetEpochSeconds)
        // V4-444: and how long the window is, where the upstream said so, so a surface can name the window ("5
        // hours", "Week") instead of only the instant it comes back.
        plan.windowSeconds?.let { drive.perf.setCount(PerfKeys.LIMIT_WINDOW_SECONDS, it) }
        return OutcomeTag.PLAN_LIMIT
    }
}
