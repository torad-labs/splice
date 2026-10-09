package splice.head.turn

import splice.head.usage.QuotaTracker
import splice.upstream.credentials.AccountSelection

/** The login a turn runs on and the quota tracker that login's usage lands in. They are chosen together and
 *  replaced together, so one value carries both. */
internal data class TurnAccountQuota(
    val account: AccountSelection? = null,
    val quota: QuotaTracker? = null,
)
