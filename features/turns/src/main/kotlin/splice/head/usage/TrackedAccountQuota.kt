// NEW: a selected account carries its quota owner through membership changes and late response headers.
package splice.head.usage

import splice.core.usage.QuotaSnapshot
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.AccountSelection

public class TrackedAccountQuota(
    private val tracker: QuotaTracker,
    private val read: AccountQuotaSource? = null,
) : AccountQuotaSource {
    override val held: Boolean get() = read?.held == true

    override fun snapshot(): QuotaSnapshot? = if (read == null) tracker.snapshot() else read.snapshot()

    /** What a turn reads when the account it selected carries no tracker: the session's sticky account, else the
     *  head's primary. */
    internal fun interface Fallback {
        fun tracker(): QuotaTracker?
    }

    /** The one place that decides which tracker a turn reads: the SELECTED account's, else the [fallback]'s. The
     *  tracker an account carries is private to [TrackedAccountQuota] and leaves it only through here, already
     *  ranked against the fallback, so no site can take the selected tracker and the primary separately and put
     *  them in the wrong order. The fallback runs only when the selection carries no tracker. */
    internal class Selected(private val fallback: Fallback) {
        fun of(selection: AccountSelection?): QuotaTracker? =
            (selection?.account?.quota as? TrackedAccountQuota)?.tracker ?: fallback.tracker()
    }
}
