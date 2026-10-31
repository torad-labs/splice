// NEW: V4-99 item 5 — the SEAL CONTRACT as its own collaborator.
//
// TurnStreamer and CollectTurn each held the whole TurnDriver, but each used exactly ONE thing from
// it — driveSealingCancellation — which meant two entries that need a drive driven both depended on
// the class that owns every other drive concern as well. That is the shape where a change to an
// unrelated part of the driver reaches two files that never wanted it: the dependency is the whole
// object when the contract is one function.
//
// WHAT THE CONTRACT IS, and why it is not merely driveOneTurn: a drive that fails must be turned
// into an honest ending (failures.catchingTurnFailure plus ending.emitFailure), and a drive that is
// CANCELLED — a head stop, a client that went away — must have its slot and usage settled by the
// cancellation seal before the throw continues. The finally block settles the account either way.
// Splitting the seal from the rest of the driver is what lets the two entries state that contract as
// their dependency, rather than borrowing the driver to reach it.
package splice.head.turn

import splice.core.failure.FailureCause
import splice.upstream.transport.UpstreamAuthMissing
import splice.upstream.transport.UpstreamEnding
import splice.upstream.transport.UpstreamFailed
import java.util.concurrent.CancellationException

internal class SealedDrive(
    private val failures: TurnFailures,
    private val oneDrive: TurnOneDrive,
    private val ending: TurnEnding,
    private val cancellationSeal: CancellationSeal,
) {

    internal suspend fun driveSealingCancellation(
        drive: TurnDrive,
        pingClient: Boolean = true,
        seal: Boolean = true,
    ) {
        try {
            failures.catchingTurnFailure { oneDrive.driveOneTurn(drive, pingClient) }
                .onSuccess { ended ->
                    if (ended != null) {
                        recordCredentialFailure(drive, ended)
                        ending.emitEnding(drive, named(drive, ended))
                    }
                }
                .onFailure { e -> ending.emitFailure(drive, e) }
        } catch (e: CancellationException) {
            cancellationSeal.stampAndSeal(drive, seal, e)
            // A head-owned cut cancelled only the child turn. Collect may still send its buffered 529 reply.
            val collectRestart = !seal && cancellationSeal.isRestart(e)
            if (!collectRestart || drive.channel.clientGone.get()) throw e
        } finally {
            if (drive.emitter.endedCleanly) drive.account?.markTurnSucceeded()
            drive.account?.releaseCredentialProbe()
            // Heard while the turn still holds its slot: a turn the head no longer counts as running has moved its
            // conversation already, when it left a request running upstream.
            drive.route?.turnEnded()
        }
    }

    private fun recordCredentialFailure(drive: TurnDrive, failure: UpstreamEnding) {
        val account = drive.account ?: return
        val cause = (failure as? UpstreamFailed)?.cause
        when {
            failure is UpstreamAuthMissing -> account.markCredentialMissing()
            // The provider refused this account's own login. A 401 naming a credential the account did not send was
            // read as the provider's own fault where it arrived (Replies' foreign-key rule, V4-242: the upstream's
            // 2026-09-25 outage shape), so the account's own stays usable and this reads only the cause.
            cause is FailureCause.Provider.AuthRejected -> account.markCredentialUnavailable()
            // The provider refused the account itself until its owner acts: held until a turn on it is served again.
            // A login the round marked before it looked for another is not marked twice: that mark retired the lease.
            cause is FailureCause.Provider.Forbidden -> cause.block?.let(account::markCredentialUnavailable)
        }
    }

    /** [ended], naming the login of a pooled turn whose account its provider refused until its owner acts: the line
     *  says which login's owner takes the step. A head that holds no pool names its account instead. */
    private fun named(drive: TurnDrive, ended: UpstreamEnding): UpstreamEnding {
        val failed = ended as? UpstreamFailed ?: return ended
        val forbidden = failed.cause as? FailureCause.Provider.Forbidden ?: return ended
        val login = drive.account?.account?.label
        return forbidden.block?.takeIf { login != null }?.let { block ->
            val cause = forbidden.copy(block = block.copy(login = login))
            UpstreamFailed(cause, failed.evidence, failed.layers, failed.planLimit, failed.native)
        } ?: ended
    }
}
