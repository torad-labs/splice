// NEW: the harness's port (docs/specs/harness.md, decision 11). What the turn asks of whatever works beside it before a
// request goes upstream, and what it gives that to work with: the request's facts, a way to hold the turn and say
// what it is doing, and the folder its session works in. The turn owns this and never depends on what implements it;
// the harness module implements it and :app hands the one to the other.
package splice.head.port

import splice.core.failure.Decline
import java.nio.file.Path

/** What works beside the turns of one head: called before each request goes upstream, for any thread, whichever turn
 *  of it this is. */
public interface TurnHarness {
    /** What the harness makes of [request], while [turn] is held if it must be. [request] is null for a request the
     *  harness reads nothing of. */
    public suspend fun around(request: HarnessRequest?, turn: HarnessTurn): HarnessEnd

    /** The head stops: every held turn stops waiting and goes on as today, and no turn is held from now on. */
    public fun stop()

    /** The head runs again after a stop: turns are held again. */
    public fun started()
}

/** What the harness needs of one turn: its id, whether it can be held, its session's folder, and a way to hold it. */
public interface HarnessTurn : TurnHolding {
    /** The id the turn is known by upstream, which the harness's records and its workers carry. */
    public val id: String

    /** False where the turn cannot be held (a request that does not stream). */
    public val holdable: Boolean

    /** The folder session [session] works in, or null when the session is unknown. */
    public fun folder(session: String?): Path?
}

/** Holds a turn while its capabilities work: the response opens and is pinged, the turn's notice is off and its
 *  place in the head's queue is given back; [release] takes one again before the turn goes on, and hears what the
 *  hold was, however it ended: the work done, a refusal, or the client leaving while it waited. */
public interface TurnHolding {
    public suspend fun hold()

    public suspend fun release(held: Held)
}

/** A hold as the turn's record keeps it: the capabilities it was held on, in order, for how long, and each one whose
 *  work failed or was stopped, as `<capability>: <reason>` (decision 6). */
public data class Held(val on: List<String>, val ms: Long, val failed: List<String> = emptyList())

/** What the harness makes of one request. */
public sealed class HarnessEnd {
    /** The request goes on carrying [texts], in order, at the end of its first user message. [skipped] names each
     *  capability its rule named that was not called, as `<capability>: <reason>` (decision 9). */
    public data class Go(val texts: List<String>, val skipped: List<String> = emptyList()) : HarnessEnd()

    /** The turn is refused, and nothing goes upstream. */
    public data class Refused(val why: Decline) : HarnessEnd()
}
