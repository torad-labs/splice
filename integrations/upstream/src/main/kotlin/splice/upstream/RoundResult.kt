// NEW: how one upstream ROUND answered, as a value (kt-no-exception-as-outcome, 2026-10-09).
//
// A round either produced the turn's honest outcome or ended without one: the upstream host refused after retries,
// there were no credentials, the connection tore before the client saw a frame and could not be re-issued, or a frame
// crossed our size limit. Those four used to travel as thrown exceptions through every runner, interceptor and the
// code-mode provider to the turn's boundary. They ride the return type now, so each layer that forwards a round has
// to say what it does with an ending, and the turn's boundary writes the one terminal for it. It is a wrapper and
// never a fourth TurnOutcome variant: the runners branch on "not a Failure", and a new variant would have reached
// their finish and health counters unannounced.
package splice.upstream

import splice.core.turn.TurnOutcome
import splice.upstream.transport.UpstreamEnding

public sealed class RoundResult {
    /** The round ran to an outcome of its own, which may itself be a failure the runners recover from. */
    public data class Outcome(public val outcome: TurnOutcome) : RoundResult()

    /** The round never produced an outcome; the turn ends on [ending] unless a layer can replace the round. */
    public class Ended(public val ending: UpstreamEnding) : RoundResult()
}
