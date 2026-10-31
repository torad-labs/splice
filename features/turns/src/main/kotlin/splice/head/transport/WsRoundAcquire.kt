// NEW: one WebSocket send binds its actual credential before the event consumer can observe its answer.
// An answer ends that credential's hold, as an HTTP answer does: claudex's turns ride the websocket, and on Oct 25
// its plan hold outlived the turns ChatGPT answered again until an HTTP request at 01:46 CT ended it.
package splice.head.transport

import splice.core.auth.AuthProvider
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.util.WallClock
import splice.head.turn.TurnDrive
import splice.upstream.Provider
import splice.upstream.WsRound
import splice.upstream.WsRoundRunner
import splice.upstream.retry.RateLimitCooldown

/** A real response.created or failure frame, without invented HTTP metadata. */
internal fun interface StreamAnswerObserver {
    fun observed(accepted: Boolean, at: Long, sender: AuthProvider, credentialKey: String?)
}

/** A consumer can report acceptance without seeing a credential, owner or private carrier key. */
internal fun interface WsRoundAnswer {
    fun observed(accepted: Boolean)
}

internal data class WsAcquiredRound(val round: WsRound, val answer: WsRoundAnswer)

/** The hold of the credential a send carries, as an HTTP send resolves it from its headers; null when none is known. */
internal fun interface CredentialHolds {
    fun forHeaders(headers: Map<String, String>, declaredCarrier: String?): RateLimitCooldown?
}

/** Captures one send, never a mutable head-wide current credential. */
internal class WsRoundAcquire(
    private val provider: Provider,
    private val answers: StreamAnswerObserver,
    private val clock: WallClock,
    private val holds: CredentialHolds,
) {
    suspend fun acquire(runner: WsRoundRunner, drive: TurnDrive, bodyJson: String): WsAcquiredRound? {
        val sender = drive.account?.account?.auth ?: provider.auth
        val credentials = sender.credentials() ?: return null
        val carrier = (credentials as? Credentials.ApiKey)?.header
        val sent = CredentialKey.headers(credentials, drive.turnHeaders)
        val key = CredentialKey.fromHeaders(sent, carrier)
        // Taken before the send, as an HTTP send takes it, so a refusal learned after posting outlives this answer.
        val receipt = (drive.account?.account?.cooldown ?: holds.forHeaders(sent, carrier))?.acceptance()
        val answer = WsRoundAnswer { accepted ->
            answers.observed(accepted, clock(), sender, key)
            if (accepted) receipt?.invoke()
        }
        val postedAtMs = drive.perf.elapsedMs()
        val round = runner.attempt(bodyJson, drive.meta, drive.turnHeaders, credentials, drive.perf) ?: return null
        return WsAcquiredRound(
            round.copy(events = UpstreamEventTiming(drive.perf, postedAtMs).observe(round.events)),
            answer,
        )
    }
}
