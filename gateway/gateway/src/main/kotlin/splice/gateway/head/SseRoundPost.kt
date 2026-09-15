// NEW: the SSE UpstreamClient.post wiring (headers/retry/rate-limit/amend).
// Split from SseRoundDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.gateway.head

import splice.core.turn.TurnOutcome
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.spi.AuthRefreshObserver
import splice.spi.PostContext
import splice.spi.Provider
import splice.spi.RetryNotice
import splice.spi.UpstreamClient

internal class SseRoundPost(
    private val provider: Provider,
    private val upstream: UpstreamClient,
    private val usageStore: UsageStore,
    private val quota: QuotaTracker?,
    private val consume: SseRoundConsume,
    private val onRetry: RetryNotice,
) {
    suspend fun post(inputs: WsRoundInputs): TurnOutcome {
        val drive = inputs.drive
        val selection = drive.account
        val account = selection?.account
        val activeQuota = drive.quota ?: quota
        return upstream.post(
            PostContext(
                url = provider.upstreamUrl,
                auth = account?.auth ?: provider.auth,
                extraHeaders = { creds ->
                    // The account's headers ride ON TOP of the provider's, never instead of them.
                    provider.extraHeaders(creds) +
                        account?.extraHeaders?.invoke(creds).orEmpty() +
                        drive.turnHeaders
                },
                onRetry = onRetry,
                perf = drive.perf,
                clientFrameEmitted = inputs.frameEmittedThisRound,
                amendBodyOnFailure = provider::amendBodyOnFailure,
                rateLimitCooldown = account?.cooldown,
                remainingTurnWait = drive.remainingTurnWait,
                authRefreshObserver = AuthRefreshObserver { selection?.markCredentialRefreshSucceeded() },
            ),
            inputs.bodyJson,
        ) { resp ->
            // Persist upstream rate-limit headers for /api/usage + statusline soft-warn (Node
            // codex-proxy wired this; the Kotlin split dropped the call site).
            usageStore.persistRateLimit { name -> resp.header(name) }
            // Quota windows the same way: Anthropic's unified family on a passthrough head, the
            // x-codex family on a Codex round. Most upstreams carry neither; then nothing moves.
            activeQuota?.observe { name -> resp.header(name) }
            consume.consume(inputs, resp)
        }
    }
}
