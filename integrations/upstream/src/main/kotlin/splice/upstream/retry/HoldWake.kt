// NEW: failure-handling slice 5 (2026-10-27). What a held turn is asked to hold for, how its hold ended, and what wakes
// a login's held turns before their timer (final-design.md section 9, "What wakes it, whichever comes first").
//
// RetryEngine names a hold; the attempt loop asks the turn's waits to hold it (TurnWaits.hold) and takes the step the
// hold's end calls for. The head keeps the hold: its line, its timer, its login's probe schedule and the signals that
// wake it early. Every other module only publishes a signal (HoldWakes), so nothing outside the head waits.
package splice.upstream.retry

import splice.core.failure.FailureCause
import splice.core.failure.Position
import java.time.Instant

/** One hold to keep: the [cause] the turn met at [at], held until [until] where its cause stated a time, probed as
 *  [probes] says, on the login [login] the turn's sends go out on ("" for a head with one). [heldSinceMs] is when this
 *  turn's first hold began, on the wall clock, so a hold that is entered again keeps its place among the login's
 *  held turns and its seven days. */
public data class HoldAsk(
    val cause: FailureCause,
    val at: Position,
    val until: Instant?,
    val probes: Probes,
    val login: String,
    val heldSinceMs: Long,
)

/** How a hold ended. */
public enum class HoldEnd {
    /** The timer, a probe or a wake came: the request goes again on the same login. */
    RESUME,

    /** A login joined the head: the turn moves to another login. */
    LOGIN_JOINED,

    /** The session's wall came first: the turn ends, and the client is told the reset and waits itself. */
    WALL,

    /** The hold reached its seven days. */
    MAX_HOLD,

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

    /** A login joined the head, so a turn held on another login can move to it. */
    public data object Joined : HoldWake()

    /** The console asked to try a held login now ([login] null: every login of the head). */
    public data class TryNow(val login: String?) : HoldWake()
}

/** Where a signal that may wake held turns is published: the head's holds hear it. */
public fun interface HoldWakes {
    public fun wake(wake: HoldWake)
}
