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
                        ending.emitEnding(drive, ended)
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
        when {
            failure is UpstreamAuthMissing -> account.markCredentialMissing()
            // The provider refused this account's own login. A 401 naming a credential the account did not send was
            // read as the provider's own fault where it arrived (Replies' foreign-key rule, V4-242: the upstream's
            // 2026-09-25 outage shape), so the account's own stays usable and this reads only the cause.
            failure is UpstreamFailed && failure.cause is FailureCause.Provider.AuthRejected ->
                account.markCredentialUnavailable()
        }
    }
}
