// PORT-OF: UsageHud.kt @ d8653a0 — invariants unchanged: the codec-to-store hand-off, moved
// verbatim onto its own file (HD-24, 2026-08-17). Widened from private to internal: it now crosses
// RateLimitHeaders.kt (producer) and RateLimitStore.kt (consumer).
package splice.gateway.usage

import splice.core.usage.RateLimitState

/** Pending ratelimit payload paired with its already-parsed state, so readRateLimit() — polled
 *  every /statusline tick and every /api/usage request — serves [parsed] straight from memory
 *  instead of re-running json.parseToJsonElement on [encoded] per call (review 2026-07-22). */
internal data class PendingRateLimit(val encoded: String, val parsed: RateLimitState)
