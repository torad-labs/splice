// NEW: the SSE UpstreamClient.post wiring (headers/retry/rate-limit/amend).
// Split from SseRoundDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.head.transport

import kotlinx.coroutines.flow.emptyFlow
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.head.admission.TurnQuota
import splice.head.usage.TurnProviderAnswers
import splice.head.usage.UsageStore
import splice.upstream.Provider
import splice.upstream.RetryNotice
import splice.upstream.TurnSignals
import splice.upstream.retry.WatchdogFired
import splice.upstream.transport.AuthRefreshObserver
import splice.upstream.transport.PostContext
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamPost

internal class SseRoundPost(
    private val provider: Provider,
    private val upstream: UpstreamClient,
    private val usageStore: UsageStore,
    private val turnQuota: TurnQuota,
    private val consume: SseRoundConsume,
    private val onRetry: RetryNotice,
) {
    /** codex-rs client.rs:2336-2340 validates HTTP echoes; WS retains the untouched raw snapshot. */
    private fun httpRoutingHeaders(inputs: WsRoundInputs): Map<String, String> {
        val holder = inputs.drive.meta.upstreamHeaders
        return holder.snapshot().filterValues { value ->
            // OkHttp's request builder accepts only TAB or printable ASCII, stricter than Ktor's check.
            val valid = value.all { it == '\t' || it in ' '..'~' }
            if (!valid && holder.claimHttpOmissionNotice()) onRetry("omitting invalid upstream turn-state HTTP header")
            valid
        }
    }

    suspend fun post(inputs: WsRoundInputs): TurnOutcome {
        while (true) {
            when (val posted = postAccount(inputs)) {
                is UpstreamPost.Delivered -> return posted.value
                is UpstreamPost.Refused -> {
                    if (inputs.drive.accountHandoff?.move(inputs.drive) != true) throw posted.failure
                }
                UpstreamPost.TurnWaitExhausted -> return waitExhausted(inputs)
            }
        }
    }

    private suspend fun postAccount(inputs: WsRoundInputs): UpstreamPost<TurnOutcome> {
        val drive = inputs.drive
        val selection = drive.account
        val account = selection?.account
        val sender = account?.auth ?: provider.auth
        val answers = TurnProviderAnswers(usageStore, sender, provider.auth)
        val activeQuota = turnQuota.forSession(drive.meta.sessionId, drive.account)
        return upstream.post(
            PostContext(
                url = provider.upstreamUrl,
                auth = sender,
                extraHeaders = { creds ->
                    // The account's headers ride ON TOP of the provider's, never instead of them.
                    val headers = provider.extraHeaders(creds) +
                        account?.extraHeaders?.invoke(creds).orEmpty() +
                        CallerCredential.over(drive.turnHeaders, creds) + httpRoutingHeaders(inputs)
                    val key = CredentialKey.fromHeaders(
                        CredentialKey.headers(creds, headers),
                        (creds as? Credentials.ApiKey)?.header,
                    )
                    answers.sent(key)
                    drive.observeAccount(creds, headers)
                    headers
                },
                onRetry = onRetry,
                perf = drive.perf,
                clientFrameEmitted = inputs.frameEmittedThisRound,
                amendBodyOnFailure = provider::amendBodyOnFailure,
                rateLimitCooldown = account?.cooldown,
                remainingTurnWait = drive.remainingTurnWait,
                authRefreshObserver = AuthRefreshObserver { selection?.markCredentialRefreshSucceeded() },
                // V4-174: the trace hears every send of this round from inside the retry loop.
                wire = drive.trace,
            ).also { context ->
                context.providerAnswerObserver = answers
                context.relayRateLimitReplies = provider.relayRateLimitReplies
                context.bodyRefusedAsTooLarge = inputs.refusedAsTooLarge()
                context.upstreamAccepted = splice.upstream.StreamStart {
                    drive.accountHandoff?.commit()
                    drive.upstreamAccepted?.invoke()
                }
            },
            inputs.body,
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
        return outcome
    }
}
