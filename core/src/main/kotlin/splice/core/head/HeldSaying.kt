// What a held turn says on its session's status line (docs/specs/harness.md, decisions 7 and 8), as a head hands it to
// the status line. Beside the Head contract, which carries it as [Head.heldSayings].
package splice.core.head

/** What each turn a head holds says right now, read at each tick of a status line. */
public fun interface HeldSayings {
    public fun now(): List<HeldSaying>
}

/** What one held turn says on its session's status line (docs/specs/harness.md, decision 7): [message], drawn in
 *  [color] (one of the status line's colour names), said at [saidAtEpochMs] on the wall clock. [session] is the
 *  client's session id when it sent one, which is how that session's line finds its own turns. Whoever holds the turn
 *  writes what it says; the reading knows nothing of why it is held. */
public data class HeldSaying(
    val session: String?,
    val message: String,
    val color: String,
    val saidAtEpochMs: Long,
)
