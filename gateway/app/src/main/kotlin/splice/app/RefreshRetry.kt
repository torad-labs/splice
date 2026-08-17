// NEW: shared classify/retry loop for the three :app-layer token-refresh HTTP calls (G7)
// (kimiRefresh/grokRefresh/codexRefresh). Extracted from KimiRefresh.kt, which already had the
// correct shape — grok/codex previously collapsed every non-2xx status AND any thrown exception
// (DNS blip, connect timeout) straight to null, indistinguishable from a dead refresh token.
// Invariants preserved from Kimi: 3 attempts, exponential backoff (now with ±10% jitter, same
// shape as provider-spi/UpstreamClient.kt's JITTER_LO/JITTER_HI); 401/403/invalid_grant are
// terminal; 429/500/502/503/504 are retryable; a thrown exception during a single attempt is
// treated as retryable, not a permanent failure.
package splice.app

import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.spi.ProcessWaiter
import splice.spi.Waiter
import kotlin.random.Random

internal const val REFRESH_MAX_ATTEMPTS = 3
private const val REFRESH_BACKOFF_BASE_MS = 1000L
private const val REFRESH_JITTER_LO = 0.9
private const val REFRESH_JITTER_HI = 1.1
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY = 429
private const val HTTP_INTERNAL = 500
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504
internal val refreshRetryableStatus = setOf(
    HTTP_TOO_MANY,
    HTTP_INTERNAL,
    HTTP_BAD_GATEWAY,
    HTTP_UNAVAILABLE,
    HTTP_GATEWAY_TIMEOUT,
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
        status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN || isInvalidGrant(body, json)

    private fun isInvalidGrant(body: String, json: Json): Boolean = Cancellables.runCatchingCancellable {
        JsonScalars.str(json.parseToJsonElement(body) as? JsonObject, "error") == "invalid_grant"
    }.getOrDefault(false)

    /**
     * Run [call] up to [maxAttempts] times, handing each response to [classify]. A thrown exception
     * from [call] or [classify] (network blip, malformed response) is treated as [RefreshStep.Retry],
     * not a permanent failure. Returns the terminal value, or null once retries are exhausted.
     */
    internal suspend fun <T> refreshWithRetry(
        maxAttempts: Int = REFRESH_MAX_ATTEMPTS,
        call: suspend () -> HttpResponse,
        classify: suspend (HttpResponse) -> RefreshStep<T>,
    ): T? {
        var attempt = 0
        while (attempt < maxAttempts) {
            val step = Cancellables.runCatchingCancellable { classify(call()) }.getOrDefault(RefreshStep.Retry)
            if (step is RefreshStep.Terminal) return step.value
            attempt++
            if (attempt < maxAttempts) {
                val base = REFRESH_BACKOFF_BASE_MS shl attempt // 2^attempt seconds
                val jittered = base * Random.nextDouble(REFRESH_JITTER_LO, REFRESH_JITTER_HI) // ±10%
                waiter.wait(jittered.toLong())
            }
        }
        return null
    }
}
