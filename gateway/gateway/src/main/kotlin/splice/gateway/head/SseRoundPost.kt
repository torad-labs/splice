// NEW: the SSE UpstreamClient.post wiring (headers/retry/rate-limit/amend).
// Split from SseRoundDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.gateway.head

import splice.core.turn.TurnOutcome
import splice.gateway.usage.UsageStore
import splice.spi.PostContext
import splice.spi.Provider
import splice.spi.RetryNotice
import splice.spi.UpstreamClient

internal class SseRoundPost(
    private val provider: Provider,
    private val upstream: UpstreamClient,
    private val usageStore: UsageStore,
    private val consume: SseRoundConsume,
    private val onRetry: RetryNotice,
) {
    suspend fun post(inputs: WsRoundInputs): TurnOutcome {
        val drive = inputs.drive
        return upstream.post(
            PostContext(
                url = provider.upstreamUrl,
                auth = provider.auth,
                extraHeaders = { creds -> provider.extraHeaders(creds) + drive.turnHeaders },
                onRetry = onRetry,
                perf = drive.perf,
                clientFrameEmitted = inputs.frameEmittedThisRound,
                amendBodyOnFailure = provider::amendBodyOnFailure,
            ),
            inputs.bodyJson,
        ) { resp ->
            // Persist upstream rate-limit headers for /api/usage + statusline soft-warn (Node
            // codex-proxy wired this; the Kotlin split dropped the call site).
            usageStore.persistRateLimit { name -> resp.header(name) }
            consume.consume(
                drive,
                inputs.sink,
                inputs.scope,
                inputs.turnJob,
                inputs.eventsBase,
                resp,
                inputs.frameEmittedThisRound,
            )
        }
    }
}
