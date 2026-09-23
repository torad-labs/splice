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

import io.ktor.http.HttpStatusCode
import splice.upstream.transport.UpstreamAuthMissing
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
                .onFailure { e ->
                    recordCredentialFailure(drive, e)
                    ending.emitFailure(drive, e)
                }
        } catch (e: CancellationException) {
            cancellationSeal.sealAndStamp(drive, seal, e)
            throw e
        } finally {
            if (drive.emitter.endedCleanly) drive.account?.markTurnSucceeded()
            drive.account?.releaseCredentialProbe()
        }
    }

    private fun recordCredentialFailure(drive: TurnDrive, failure: Throwable) {
        when {
            failure is UpstreamAuthMissing -> drive.account?.markCredentialMissing()
            failure is UpstreamFailed && failure.status == HttpStatusCode.Unauthorized.value ->
                drive.account?.markCredentialUnavailable()
        }
    }
}
