// NEW: failure-handling slice 5 (2026-10-27). What a held turn is asked to hold for, how its hold ended, and what wakes
// a login's held turns before their timer (final-design.md section 9, "What wakes it, whichever comes first").
//
// RetryEngine names a hold; the attempt loop asks the turn's waits to hold it (TurnWaits.hold) and takes the step the
// hold's end calls for. The head keeps the hold: its line, its timer, its login's probe schedule and the signals that
// wake it early. Every other module only publishes a signal (HoldWakes), so nothing outside the head waits.
package splice.upstream.retry

import splice.core.failure.FailureCause
import splice.core.failure.Outlast
import splice.core.failure.Position
import java.time.Instant

/** One hold to keep: the [cause] the turn met at [at], held until [until] where its cause stated a time, probed as
 *  [probes] says, on the login [login] the turn's sends go out on ("" for a head with one). [heldSinceMs] is when this
 *  turn's first hold began, on the wall clock, so a hold that is entered again keeps its place among the login's
 *  held turns and its seven days. [moves] says what may take the turn off its login before its cause lifts. */
public data class HoldAsk(
    val cause: FailureCause,
    val at: Position,
    val until: Instant?,
    val probes: Probes,
    val login: String,
    val heldSinceMs: Long,
    val moves: HoldMoves = HoldMoves(),
)

/** What may take a held turn off its login before its cause lifts. [login] answers whether another login could take
 *  the turn now, which a login that joins asks before the hold ends for it: a turn no login can serve keeps waiting;
 *  null ends the hold on any join. [model] answers whether the turn's re-send would go on under another model some
 *  login serves now, which the hold asks every [MODEL_ROOM_CHECK_MS] (Nov 17, 2026); null never asks. */
public data class HoldMoves(val login: OtherLoginFree? = null, val model: OtherModelRoom? = null)

// why: a held turn asks this often whether another model has room, so a session held on a spent model goes on under
// one that has room within a minute of it having room, and a hold of days asks a few thousand cheap questions at most.
public const val MODEL_ROOM_CHECK_MS: Long = 60_000L

/** Whether another login can take this failure before content is committed. */
public fun interface OtherLoginFree {
    public operator fun invoke(cause: FailureCause): Boolean
}

/** Whether the turn's re-send would go on under another model, one some login can serve now, while no login can serve
 *  the model it asks for (Nov 17, 2026): then no hold begins for that model. The cause supplies the just-received
 *  reset of the turn's own login before the post publishes that login's hold. */
public fun interface OtherModelRoom {
    public operator fun invoke(cause: FailureCause): Boolean
}

/** How a hold ended; [outlast] says how the turn ends where it ends without going on. */
public enum class HoldEnd(public val outlast: Outlast? = null) {
    /** The timer, a probe or a wake came: the request goes again on the same login. */
    RESUME,

    /** A login joined the head that can take the turn: the turn moves to it. */
    LOGIN_JOINED,

    /** The session's wall came first: the turn ends, and the client is told the reset and waits itself. */
    WALL(Outlast.WALL),

    /** The hold reached its seven days. */
    MAX_HOLD(Outlast.MAX_HOLD),

    /** Another model has room while every login is held for this one: the turn ends at once as a re-send that goes on
     *  under it (Nov 17, 2026). */
    MODEL_ROOM(Outlast.MOVES),

    /** The client of a turn that outlives it (a compaction) left while it held: nothing more is sent. */
    CLIENT_GONE,

    /** Nothing here holds a turn (a post with no turn around it): the turn ends as it did before holds. */
    UNSUPPORTED,
}

/** What wakes a login's held turns before their timer. */
public sealed class HoldWake {
    /** A request on [login] was answered 2xx: its limit lifted. */
    public data class Served(val login: String) : HoldWake()

    /** A quota reading says the head's limit lifted: its window reads allowed again. */
    public data object Allowed : HoldWake()

    /** A login joined the head, or one already there became another account's or was signed in again: a turn held on
     *  another login moves to it where it can serve the turn, and goes on waiting where it cannot. */
    public data object Joined : HoldWake()

    /** The console asked to try a held login now ([login] null: every login of the head). */
    public data class TryNow(val login: String?) : HoldWake()
}

/** Where a signal that may wake held turns is published: the head's holds hear it. */
public fun interface HoldWakes {
    public fun wake(wake: HoldWake)
}
