// PORT-OF: UsageHud.kt (UsageStore) @ d8653a0 — invariants unchanged: the ratelimit header codec
// (parse/format only, no lock, no file), moved verbatim onto its own collaborator (HD-24,
// 2026-08-17).
package splice.gateway.usage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.usage.RateLimitState
import splice.core.util.WallClock

/**
 * Reads one response header by name from the upstream round that just completed, or null when it
 * is absent.
 *
 * A LOOKUP over an already-received response, not a request: [UsageHud.persistRateLimit] is handed
 * this so it can pull the `x-ratelimit-*` family without the hud ever seeing the response object.
 * Absent headers are the ordinary case — most upstreams send no rate-limit family at all, and the
 * whole persist is a no-op without a limit.
 */
public fun interface HeaderLookup {
    public operator fun invoke(name: String): String?
}

/** Ratelimit header codec: HeaderLookup in, [PendingRateLimit] or a parsed [RateLimitState] out. */
public class RateLimitHeaders(private val clock: WallClock) {
    private val usageJson = UsageJson()

    /** Parses x-ratelimit-limit-tokens / -remaining-tokens / -reset-tokens into a pending payload
     *  paired with its already-parsed state (see [PendingRateLimit]); null without a limit.
     *  `internal`, not `public`: it returns the internal [PendingRateLimit]. */
    internal fun pendingFrom(header: HeaderLookup): PendingRateLimit? {
        val limit = header("x-ratelimit-limit-tokens")?.toLongOrNull() ?: return null
        val remaining = header("x-ratelimit-remaining-tokens")?.toLongOrNull()
        val reset = header("x-ratelimit-reset-tokens")?.takeIf { it.isNotEmpty() }
        val payload = buildJsonObject {
            put("limit_tokens", limit)
            // NB: JsonObjectBuilder.put returns the PREVIOUS value (null on first insert) —
            // an elvis on it double-puts. Explicit branches only.
            if (remaining != null) put("remaining_tokens", remaining) else put("remaining_tokens", null as String?)
            if (reset != null) put("reset_tokens", reset) else put("reset_tokens", null as String?)
            put("updated_at", clock())
        }
        return PendingRateLimit(payload.toString() + "\n", rateLimitStateFrom(payload))
    }

    /** RateLimitState field mapping, single-sourced so the pending-payload and on-disk paths
     *  cannot drift (review 2026-07-22 round 3). Widened from private to internal (HD-24):
     *  RateLimitStore's on-disk read path calls it from a different file, and internal is already
     *  module-wide — `public` grew :gateway's API for a function nothing outside it reads (review
     *  2026-08-28, PR 99). */
    internal fun rateLimitStateFrom(obj: JsonObject): RateLimitState = RateLimitState(
        limitTokens = usageJson.num(obj["limit_tokens"]),
        remainingTokens = usageJson.num(obj["remaining_tokens"]),
        resetTokens = (obj["reset_tokens"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        updatedAt = usageJson.num(obj["updated_at"]),
    )
}
