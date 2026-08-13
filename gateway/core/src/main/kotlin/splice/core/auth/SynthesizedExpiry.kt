// NEW: (SH-01, no Node source) the ONE missing-expiry policy. G18 fixed grok's never-expiring
// credential (no usable expiry => cached forever, first signal a mid-turn 401) by synthesizing
// mtime+4h; codex had the identical hole and kimi floored to 0 (a refresh per call). One helper so
// a fourth provider cannot invent a fourth behaviour. The synthesized ceiling is only ever a floor
// on STALENESS — it can force an extra refresh, never suppress one.
package splice.core.auth

/** 4h: long enough that a healthy file never re-refreshes inside a session, short enough that a
 *  shape-drifted auth.json (opaque token, exp-less JWT, foreign CLI write) ages out same-day. */
public const val SYNTHETIC_EXPIRY_TTL_MS: Long = 4 * 60 * 60 * 1000L

/** The expiry to use when a credential file carries none: its mtime plus [SYNTHETIC_EXPIRY_TTL_MS]. */
public fun synthesizedExpiryMs(mtimeMs: Long): Long = mtimeMs + SYNTHETIC_EXPIRY_TTL_MS
