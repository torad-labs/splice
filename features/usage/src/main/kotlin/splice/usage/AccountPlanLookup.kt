// NEW: Oct 11, 2026 (fin) — whether a plan covers a request is a fact about its ACCOUNT, answered once for every
// reader: the perf rows' split, the hourly rollup's split, and the Day row's flag that all of a command's accounts
// are plan accounts. A second copy of this decision is a page whose Requests and Accounts disagree about who paid.
package splice.usage

import splice.accounts.pool.HeadAccountPoolSource

/** The plan each account of one command runs on, as the command reports it now. An account the command's pool names
 *  answers for itself, an API key with no plan included; any other account falls back to the command's own quota. */
public class AccountPlanLookup(
    /** The command's billing, from its credential; null when the caller does not know it. */
    public val kind: UsageBilling?,
    private val pool: HeadAccountPoolSource?,
    private val headPlan: HeadQuotaPlan,
) {
    // Read only when asked, so a window that never asks never touches the pool or the quota file.
    private val byLabel by lazy { pool?.view(null)?.accounts.orEmpty().associate { it.label to it.plan } }
    private val fallback by lazy { headPlan() }

    /** The plan [account] runs on, or null when it runs on none. */
    public fun planOf(account: String?): String? =
        if (account != null && account in byLabel) byLabel[account] else fallback

    /** Whether a plan covers [account]'s requests: a subscription command covers every account, any other command
     *  covers the accounts that have a plan. A local runtime has none. */
    public fun covers(account: String?): Boolean =
        kind != UsageBilling.LOCAL_RUNTIME && (kind == UsageBilling.SUBSCRIPTION || planOf(account) != null)

    /** Whether every account the command can use is covered, so none of its requests can be charged to a key. */
    public fun coversEveryAccount(): Boolean = when {
        kind == UsageBilling.LOCAL_RUNTIME -> false
        kind == UsageBilling.SUBSCRIPTION -> true
        byLabel.isNotEmpty() -> byLabel.values.all { it != null }
        else -> fallback != null
    }
}

/** The plan the command's own quota names, for an account its pool does not. Read when asked, so a window that never asks never touches the quota file. */
public fun interface HeadQuotaPlan {
    public operator fun invoke(): String?
}
