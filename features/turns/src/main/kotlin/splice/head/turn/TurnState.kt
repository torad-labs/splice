// NEW: failure-handling slice 5 (2026-10-27). What a live turn is doing, as status and the console list it: running,
// or held in its own request until its cause lifts (final-design.md section 9). A held turn names the reset it waits
// for, the login it waits on and the share of the heap its request keeps while it waits.
package splice.head.turn

import java.time.Instant

/** What a live turn is doing now. */
public sealed class TurnState {
    /** Sending, streaming or waiting out a short retry. */
    public data object Running : TurnState()

    /** Held until [until], the reset its cause stated, or until a probe finds it lifted where none was stated ([until]
     *  null). [cause] names what it met in a word a status table prints ("limit", "overload", "credit"); [login] is
     *  the login it waits on, [since] when it was first held, and [heapBytes] the size of its request, which it keeps
     *  in memory while it waits. */
    public data class Held(
        val until: Instant?,
        val cause: String,
        val login: String,
        val since: Instant,
        val heapBytes: Long,
    ) : TurnState()
}
