// NEW: shared classify/retry loop for the three :app-layer token-refresh HTTP calls (G7)
// (kimiRefresh/grokRefresh/codexRefresh). Extracted from KimiRefresh.kt, which already had the
// correct shape — grok/codex previously collapsed every non-2xx status AND any thrown exception
// (DNS blip, connect timeout) straight to null, indistinguishable from a dead refresh token.
// Invariants preserved from Kimi: 3 attempts, exponential backoff (now with ±10% jitter, same
// shape as upstream/UpstreamClient.kt's JITTER_LO/JITTER_HI); 401/403/invalid_grant are
// terminal; 429/500/502/503/504 are retryable; a thrown exception during a single attempt is
// treated as retryable, not a permanent failure.
package splice.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import kotlin.random.Random

internal const val REFRESH_MAX_ATTEMPTS = 3
private const val REFRESH_BACKOFF_BASE_MS = 1000L
private const val REFRESH_JITTER_LO = 0.9
private const val REFRESH_JITTER_HI = 1.1
internal val refreshRetryableStatus = setOf(
    HttpStatus.TOO_MANY_REQUESTS,
    HttpStatus.INTERNAL_SERVER_ERROR,
    HttpStatus.BAD_GATEWAY,
    HttpStatus.SERVICE_UNAVAILABLE,
    HttpStatus.GATEWAY_TIMEOUT,
)

/** One refresh attempt's verdict: terminal (a result, possibly null) or worth retrying. */
internal sealed class RefreshStep<out T> {
    data class Terminal<T>(val value: T?) : RefreshStep<T>()
    data object Retry : RefreshStep<Nothing>()
}

/** The retry loop and its terminal-failure classifier, held as a collaborator by each vendor's
 *  refresh (Kotlin style law, 2026-08-15): a helper shared by several types is a small named
 *  class they construct, not a pair of free functions. Stateless — every attempt's state lives
 *  in [refreshWithRetry]'s own frame, so one instance per vendor is as correct as one per call. */
internal class RefreshRetry(
    // HD-19: the backoff sleep, as a named port. Production wires ProcessWaiter (`delay`); a test
    // wires a recorder, which turns "3 attempts at 2s then 4s (+/-10%)" from three real seconds of
    // sleeping into an assertion on the captured intervals.
    private val waiter: Waiter = ProcessWaiter(),
) {

    /** 401/403 are terminal by status alone; invalid_grant wins even under a nominally-retryable status. */
    internal fun isTerminalRefreshFailure(status: Int, body: String, json: Json): Boolean =
        status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN || isInvalidGrant(body, json)

    /** A body that will not parse is not an invalid_grant — the status alone decides it. Both arms of
     *  the Result are written out rather than collapsed, so the parse outcome is a named decision
     *  instead of a default applied behind the reader's back. */
    private fun isInvalidGrant(body: String, json: Json): Boolean {
        val parsed = Cancellables.runCatchingCancellable { json.parseToJsonElement(body) as? JsonObject }
        return parsed.fold(
            onSuccess = { JsonScalars.str(it, "error") == "invalid_grant" },
            onFailure = { false },
        )
    }

    /**
     * Run [call] up to [maxAttempts] times, handing each response to [classify]. A thrown exception
     * from [call] or [classify] (network blip, malformed response) is treated as [RefreshStep.Retry],
     * not a permanent failure. Returns the terminal value, or null once retries are exhausted —
     * unless the FINAL attempt itself threw, which rethrows (DR-82): the swallow made
     * RefreshOutcome.TransportFailed unreachable, so a network outage reported as "refresh
     * rejected by token endpoint". The provider boundary's getOrElse owns the throw; null stays
     * the answer only when the endpoint really answered and classified every attempt Retry.
     */
    internal suspend fun <T> refreshWithRetry(
        maxAttempts: Int = REFRESH_MAX_ATTEMPTS,
        call: RefreshPost,
        classify: RefreshClassify<T>,
    ): T? {
        var attempt = 0
        var lastFailure: Throwable? = null
        while (attempt < maxAttempts) {
            val result = Cancellables.runCatchingCancellable { classify(call()) }
            lastFailure = result.exceptionOrNull()
            // Both arms are named rather than defaulted: a throw on this attempt means Retry, and the
            // failure itself is already captured above (DR-82 rethrows it once the budget is spent).
            val step = result.fold(onSuccess = { it }, onFailure = { RefreshStep.Retry })
            if (step is RefreshStep.Terminal) return step.value
            attempt++
            if (attempt < maxAttempts) {
                val base = REFRESH_BACKOFF_BASE_MS shl attempt // 2^attempt seconds
                val jittered = base * Random.nextDouble(REFRESH_JITTER_LO, REFRESH_JITTER_HI) // ±10%
                waiter.wait(jittered.toLong())
            }
        }
        lastFailure?.let { throw it }
        return null
    }
}
