// NEW: (SH-01, no Node source) the ONE missing-expiry policy. G18 fixed grok's never-expiring
// credential (no usable expiry => cached forever, first signal a mid-turn 401) by synthesizing
// mtime+4h; codex had the identical hole and kimi floored to 0 (a refresh per call). One helper so
// a fourth provider cannot invent a fourth behaviour. The synthesized ceiling is only ever a floor
// on STALENESS — it can force an extra refresh, never suppress one.
// Named object since the 2026-08-16 style migration (HD-M8): both arguments are epoch millis, so
// there is no receiver type. The TTL stays a top-level `const val` — the law's sanctioned home for
// constants, and grok imports it directly.
package splice.core.auth

/** 4h: long enough that a healthy file never re-refreshes inside a session, short enough that a
 *  shape-drifted auth.json (opaque token, exp-less JWT, foreign CLI write) ages out same-day. */
public const val SYNTHETIC_EXPIRY_TTL_MS: Long = 4 * 60 * 60 * 1000L

public object CredentialExpiry {

    /** The expiry to use when a credential file carries none: its mtime plus [SYNTHETIC_EXPIRY_TTL_MS].
     *  AUTH-003: [mtimeMs] is clamped to at most [nowMs] first — a credential file whose mtime reads
     *  in the future (clock skew, container/VM resume, hibernate) must not push the synthesized
     *  ceiling further out than a healthy clock would; it can only ever be as stale as "now". */
    public fun synthesizedExpiryMs(mtimeMs: Long, nowMs: Long): Long =
        minOf(mtimeMs, nowMs) + SYNTHETIC_EXPIRY_TTL_MS

    /** DR-177: the instant a credential granted NOW for [lifetimeSeconds] expires — the one
     *  conversion the providers share, instead of three spellings of `now + seconds * 1000`.
     *
     *  None of those spellings was total. `seconds * 1000` WRAPS for any lifetime past
     *  Long.MAX_VALUE/1000, and the sum wraps well before that, so a garbled or hostile expires_in
     *  produced a large NEGATIVE instant: the credential reads expired on every turn, every turn
     *  refreshes, and the refresh returns the same bad field — a permanent storm out of one number.
     *
     *  Neither end saturates to Long.MAX_VALUE, which would hand back exactly the never-expiring
     *  credential SH-01 above exists to abolish. A lifetime that cannot be represented is not a
     *  usable expiry, and "no usable expiry" is precisely the case this file already rules on, so
     *  it degrades to the synthesized ceiling. A non-positive lifetime clamps to [nowMs]: already
     *  expired either way — the provider's own post-refresh stale-floor backoff owns the pathology
     *  from there — but without a bogus pre-epoch instant travelling downstream.
     *
     *  Both directions can only ever force an EXTRA refresh, never suppress one: the invariant
     *  stated at the top of this file, and the reason neither clamp can land below the status quo. */
    public fun expiryFromNowMs(nowMs: Long, lifetimeSeconds: Long): Long {
        if (lifetimeSeconds <= 0) return nowMs
        val lifetimeMs =
            if (lifetimeSeconds > Long.MAX_VALUE / MS_PER_S) Long.MAX_VALUE else lifetimeSeconds * MS_PER_S
        // Two non-negative Longs sum to something SMALLER than either only by wrapping, which is
        // the check the multiply-side guard alone cannot make: a lifetime can be perfectly
        // representable in milliseconds and still overflow once added to the clock.
        val expiresAt = nowMs + lifetimeMs
        return if (expiresAt < nowMs) nowMs + SYNTHETIC_EXPIRY_TTL_MS else expiresAt
    }

    /** DR-177: an ABSOLUTE epoch-seconds claim (a JWT `exp`) in milliseconds, or null when it does
     *  not fit — which is the same "no usable expiry" the callers already answer with
     *  [synthesizedExpiryMs], so an unrepresentable claim now takes the path a missing one takes.
     *  Distinct from [expiryFromNowMs] because this is a point in time, not a lifetime: there is no
     *  clock to add and a negative value is not "expired now", it is nonsense. */
    public fun epochSecondsToMs(epochSeconds: Long): Long? =
        if (epochSeconds < 0 || epochSeconds > Long.MAX_VALUE / MS_PER_S) null else epochSeconds * MS_PER_S
}

private const val MS_PER_S = 1000L
