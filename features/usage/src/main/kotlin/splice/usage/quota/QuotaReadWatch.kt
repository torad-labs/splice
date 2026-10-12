// NEW: names why a login's limits read failed, for Accounts' "Not read" (Oct 12, 2026). It wraps a login's probe and
// reports each read's end to [QuotaReadOutcome]: null when the provider answered, else a reason key and a time. The
// reason is told from the failure's type, never from its text, so no body, token or vendor message rides along.
package splice.usage.quota

import splice.core.usage.QuotaReadFailure
import splice.core.usage.QuotaReadOutcome
import splice.core.usage.QuotaReadReason
import splice.core.usage.QuotaSnapshot
import splice.core.util.Cancellables
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import java.io.IOException

public class QuotaReadWatch(
    private val probe: QuotaProbe,
    private val outcome: QuotaReadOutcome,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) : QuotaProbe {
    override suspend fun probe(): QuotaSnapshot? = Cancellables.runCatchingCancellable { probe.probe() }.fold(
        onSuccess = { snapshot ->
            outcome.read(null)
            snapshot
        },
        onFailure = { failure ->
            outcome.read(QuotaReadFailure(reason(failure), clock() / MS_PER_SECOND))
            throw failure
        },
    )

    private fun reason(failure: Throwable): QuotaReadReason = when (failure) {
        is QuotaUnreadable -> failure.reason
        is QuotaEndpointRefused ->
            if (failure.status in SIGNED_OUT_STATUSES) QuotaReadReason.SIGNED_OUT else QuotaReadReason.REFUSED
        is UsageAnswerError -> QuotaReadReason.REFUSED
        is IOException -> QuotaReadReason.NOT_ANSWERING
        else -> QuotaReadReason.REFUSED
    }
}

/** A login splice cannot read limits for at all: no credential ([QuotaReadReason.SIGNED_OUT]), or a forwarded sign-in
 *  that carries no token of splice's ([QuotaReadReason.UNSUPPORTED]). An answer, so the poller waits its interval. */
internal class QuotaUnreadable(val reason: QuotaReadReason) : IOException("no limits splice can read for this login")

// why: the statuses a usage endpoint answers when the credential itself is refused.
private val SIGNED_OUT_STATUSES = setOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)

private const val MS_PER_SECOND = 1_000L
