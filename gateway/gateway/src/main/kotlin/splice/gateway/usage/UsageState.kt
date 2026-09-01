// PORT-OF: UsageHud.kt @ d8653a0 — invariants unchanged: the read-model handed to FileSources and
// statusline, moved verbatim onto its own file (HD-24, 2026-08-17).
package splice.gateway.usage

import splice.core.usage.RateLimitState

public data class UsageState(
    val windowHours: Int,
    val entries: Int,
    val outputTokens5h: Long,
    val ratelimit: RateLimitState?,
)
