// NEW: the row /api/accounts joins per credential, split out of AccountsRoute.kt (concentration, Oct 12): the
// credential it stands for, why it cannot be selected now, its yes/no facts, and every head riding it.
package splice.accounts.pool

/** Why a pooled or single-login account cannot be selected right now, or all null when it can. [refusal]
 *  (V4-410) is the permanent kind: splice will not load that credential at all, so it is not a renewal. */
internal data class AuthExclusionView(
    val untilEpochMillis: Long?,
    val reason: String?,
    val refusal: String? = null,
    val ref: String? = null,
    val displayName: String? = null,
    val email: String? = null,
)

/** The three yes/no/unknown facts a console row renders as booleans — grouped for the same
 *  constructor-width reason as [QuotaWindowView]. */
internal data class AccountFlags(
    val available: Boolean?,
    val credentialPresent: Boolean,
    val selected: Boolean?,
)

/** The credential a joined row stands for: where it lives, which auth kind owns it, and whether it is a head's
 *  single login rather than a pooled account. */
internal data class JoinedCredential(val path: String?, val kind: String, val singleLogin: Boolean)

/** One joined row: an OAuth account (or a single-login head with none) plus every head riding it.
 *  [merge] retains each head's own selector in [labelsByHead] as it joins the same credential path. */
internal data class JoinedAccount(
    val credential: JoinedCredential,
    val label: String?,
    val primary: Boolean,
    val quota: JoinedQuota,
    val authExclusion: AuthExclusionView,
    val flags: AccountFlags,
    val labelsByHead: MutableMap<String, String?> = sortedMapOf(),
) {
    /** The heads on which THIS credential is the pin: a credential two heads share can be pinned on one. */
    val pinnedHeads: MutableSet<String> = sortedSetOf()
    val heads: Set<String> get() = labelsByHead.keys
}
