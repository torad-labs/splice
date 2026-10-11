// NEW: one WebSocket send binds its actual credential before the event consumer can observe its answer.
package splice.head.transport

import splice.core.auth.AuthProvider
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.util.WallClock
import splice.head.turn.TurnDrive
import splice.upstream.Provider
import splice.upstream.WsRound
import splice.upstream.WsRoundRunner

/** A real response.created or failure frame, without invented HTTP metadata. */
internal fun interface StreamAnswerObserver {
    fun observed(accepted: Boolean, at: Long, sender: AuthProvider, credentialKey: String?)
}

/** A consumer can report acceptance without seeing a credential, owner or private carrier key. */
internal fun interface WsRoundAnswer {
    fun observed(accepted: Boolean)
}

internal data class WsAcquiredRound(val round: WsRound, val answer: WsRoundAnswer)

/** Captures one send, never a mutable head-wide current credential. */
internal class WsRoundAcquire(
    private val provider: Provider,
    private val answers: StreamAnswerObserver,
    private val clock: WallClock,
) {
    suspend fun acquire(runner: WsRoundRunner, drive: TurnDrive, bodyJson: String): WsAcquiredRound? {
        val sender = drive.account?.account?.auth ?: provider.auth
        val credentials = sender.credentials() ?: return null
        val key = CredentialKey.fromHeaders(
            CredentialKey.headers(credentials, drive.turnHeaders),
            (credentials as? Credentials.ApiKey)?.header,
        )
        val answer = WsRoundAnswer { accepted -> answers.observed(accepted, clock(), sender, key) }
        val postedAtMs = drive.perf.elapsedMs()
        val round = runner.attempt(bodyJson, drive.meta, drive.turnHeaders, credentials, drive.perf) ?: return null
        return WsAcquiredRound(
            round.copy(events = UpstreamEventTiming(drive.perf, postedAtMs).observe(round.events)),
            answer,
        )
    }
}
