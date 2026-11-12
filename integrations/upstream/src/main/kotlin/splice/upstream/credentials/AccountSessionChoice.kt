// NEW: Oct 13, 2026 — one session's own choice on the pool, as AccountPool.listed reads it for a whole listing.
package splice.upstream.credentials

/** The session's login when membership still holds it, its pin (its own, else the head's), and its own last switch:
 *  the per-session fields of [AccountPoolView], without the head-wide account rows and credential reads. */
public data class AccountSessionChoice(
    val selectedLabel: String?,
    val pinnedLabel: String?,
    val lastSwitch: AccountSwitch?,
)
