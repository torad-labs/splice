// NEW: the SSE UpstreamClient.post wiring (headers/retry/rate-limit/amend).
// Split from SseRoundDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.head.transport

import kotlinx.coroutines.flow.emptyFlow
import splice.core.failure.FailureCause
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.head.admission.TurnQuota
import splice.head.transport.headers.SseRoundHeaders
import splice.head.usage.TurnProviderAnswers
import splice.head.usage.UsageStore
import splice.upstream.Provider
import splice.upstream.RetryNotice
import splice.upstream.RoundResult
import splice.upstream.TurnSignals
import splice.upstream.credentials.AccountSelection
import splice.upstream.retry.WatchdogFired
import splice.upstream.transport.AuthRefreshObserver
import splice.upstream.transport.OtherLoginFree
import splice.upstream.transport.PostContext
import splice.upstream.transport.PostLimits
import splice.upstream.transport.PostObservers
import splice.upstream.transport.PostRecovery
import splice.upstream.transport.PreparedPost
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.UpstreamPost

internal class SseRoundPost(
    private val provider: Provider,
    private val upstream: UpstreamClient,
    private val usageStore: UsageStore,
    private val turnQuota: TurnQuota,
    private val consume: SseRoundConsume,
    private val onRetry: RetryNotice,
) {
    private val headers = SseRoundHeaders(provider, onRetry)

    suspend fun post(inputs: WsRoundInputs): RoundResult {
        val prepared = upstream.preparation(inputs.body)
        while (true) {
            val result = when (val posted = postAccount(inputs, prepared)) {
                is UpstreamPost.Delivered -> RoundResult.Outcome(posted.value)
                // A refusal the account handoff can answer posts again, on the account it moved to.
                is UpstreamPost.Refused -> moved(inputs, posted.failure)
                is UpstreamPost.Ended -> RoundResult.Ended(posted.ending)
                UpstreamPost.TurnWaitExhausted -> RoundResult.Outcome(waitExhausted(inputs))
            }
            if (result != null) return result
        }
    }

    /** Null once the account handoff moved the turn to another account, which the round is posted on again. A login
     *  its provider refused after its renewal, or refused until its owner acts (an AccountBlock), leaves the pool
     *  first, as a turn ending on it would (SealedDrive), so the turns after this one do not start on it; marked while
     *  its lease is the current one, since a lease the handoff released marks nothing. Where no account is free, the
     *  round ends on [failure]. */
    private suspend fun moved(inputs: WsRoundInputs, failure: UpstreamFailed): RoundResult? {
        val account = inputs.drive.account
        val cause = failure.cause
        when {
            cause is FailureCause.Provider.AuthRejected -> account?.markCredentialUnavailable()
            cause is FailureCause.Provider.Forbidden -> cause.block?.let { account?.markCredentialUnavailable(it) }
        }
        val moved = inputs.drive.accountHandoff?.moveAwaitingEvidence(inputs.drive) == true
        return if (moved) null else RoundResult.Ended(failure)
    }

    private suspend fun postAccount(inputs: WsRoundInputs, prepared: PreparedPost): UpstreamPost<TurnOutcome> {
        val drive = inputs.drive
        val selection = drive.account
        val account = selection?.account
        val sender = account?.auth ?: provider.auth
        val answers = TurnProviderAnswers(usageStore, sender, provider.auth)
        val activeQuota = turnQuota.forSession(drive.meta.scope.sessionId, drive.account)
        return upstream.post(
            PostContext(
                url = provider.upstreamUrl,
                auth = sender,
                extraHeaders = { creds -> headers.send(inputs, answers, creds) },
                replies = provider.replies,
                observers = PostObservers(
                    perf = drive.perf,
                    // V4-174: the trace hears every send of this round from inside the retry loop.
                    wire = drive.trace,
                    authRefreshObserver = AuthRefreshObserver { selection?.markCredentialRefreshSucceeded() },
                    onRetry = onRetry,
                ),
                recovery = PostRecovery(
                    clientFrameEmitted = inputs.frameEmittedThisRound,
                    amendBodyOnFailure = provider::amendBodyOnFailure,
                    route = drive.route,
                ),
                limits = postLimits(inputs, selection),
            ).also { context ->
                context.providerAnswerObserver = answers
                context.bodyRefusedAsTooLarge = inputs.refusedAsTooLarge()
                context.upstreamAccepted = splice.upstream.StreamStart {
                    drive.accountHandoff?.commit()
                    drive.upstreamAccepted?.invoke()
                    drive.holdServed?.run()
                }
            },
            prepared,
        ) { resp ->
            provider.observeResponseHeaders(drive.meta, resp)
            // Persist upstream rate-limit headers for /api/usage + statusline soft-warn (Node
            // codex-proxy wired this; the Kotlin split dropped the call site).
            usageStore.persistRateLimit { name -> resp.header(name) }
            // Quota windows the same way: Anthropic's unified family on a passthrough head, the
            // x-codex family on a Codex round. Most upstreams carry neither; then nothing moves.
            activeQuota?.let(resp::observeQuota)
            consume.consume(inputs, resp)
        }
    }

    /** The selected login's cooldown and the turn's remaining wait share one retry admission decision. */
    private fun postLimits(inputs: WsRoundInputs, selection: AccountSelection?): PostLimits {
        val drive = inputs.drive
        return PostLimits(
            rateLimitCooldown = selection?.account?.cooldown,
            remainingTurnWait = drive.remainingTurnWait,
            warmWait = selection?.warmWait,
            retry = drive.retry,
            otherLoginFree = drive.accountHandoff?.let { handoff ->
                OtherLoginFree { cause -> handoff.otherFree(drive, cause) }
            },
        )
    }

    /** No response arrived. The existing translator owns the total-cap terminal and its no-continuation policy. */
    private suspend fun waitExhausted(inputs: WsRoundInputs): TurnOutcome {
        val drive = inputs.drive
        val totalCap = WatchdogFired.TotalCap(provider.watchdog.totalCap.inWholeMilliseconds)
        val signals = TurnSignals(
            watchdogFired = { totalCap },
            clientGone = { inputs.clientGone() },
        )
        val outcome = provider.streamTranslator(drive.meta, signals).driveTurn(emptyFlow(), inputs.sink)
        drive.perf.mark(PerfKeys.STREAM_END)
        if (inputs.requestStartedThisRound()) return outcome
        return when (outcome) {
            is TurnOutcome.Success -> outcome.copy(usage = noRequestUsage)
            is TurnOutcome.Failure -> outcome.copy(
                partial = outcome.partial?.copy(usage = noRequestUsage),
                salvagedUsage = noRequestUsage,
            )
            is TurnOutcome.ClientAbandoned -> outcome.copy(salvagedUsage = noRequestUsage)
        }
    }
}
