// NEW: what UpstreamClient.post answers, moved verbatim from UpstreamClient.kt, which keeps the loop that produces it.
package splice.upstream.transport

/**
 * What [UpstreamClient.post] answers: the handler's value, or the one refusal the loop DECIDES.
 *
 * V4-114 (kt-no-exception-as-outcome): the exhausted turn-wait budget used to arrive as a thrown
 * `UpstreamTurnWaitExhausted`, which every `catch (e: Exception)` and `runCatching` on the turn
 * path saw as indistinguishable from a broken invariant, and which no signature announced. It is a
 * VALUE the one caller (SseRoundPost) hands to the translator, so the compiler now checks that the
 * caller handled it. Every other ending is a VALUE too since 2026-10-09: [Ended] carries the sealed
 * [UpstreamEnding] (the host's HTTP refusal after retries, a missing local credential, a torn transport, an
 * oversized frame), so no ending of [UpstreamClient.post] is thrown. Raw transport errors from the engine stay
 * exceptions: they are not splice's vocabulary.
 */
public sealed class UpstreamPost<out T> {
    /** Native pooled refusal before stream handoff. The caller may switch credentials, never resend this login. */
    public data class Refused(public val failure: UpstreamFailed) : UpstreamPost<Nothing>()

    /** The upstream answered and [value] is what the caller's handler made of it. */
    public data class Delivered<T>(public val value: T) : UpstreamPost<T>()

    /** The call ended without a round: a failure after retries, a hold, missing credentials, a tear that could not be
     *  re-issued, or an oversized frame. The caller ends the turn on [ending], whatever it is. */
    public data class Ended(public val ending: UpstreamEnding) : UpstreamPost<Nothing>()

    /** The whole-turn wait budget expired BEFORE a request went out, so there is no HTTP response
     *  to classify and nothing was sent. The caller owns the terminal. */
    public data object TurnWaitExhausted : UpstreamPost<Nothing>()
}
