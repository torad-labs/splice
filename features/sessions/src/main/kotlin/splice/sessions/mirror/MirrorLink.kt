// NEW: LAN share, phase 2, milestone 6 and the round-3 review (docs/specs/lan-share.md). How a machine's mirrors here
// reach it (the carrier a mirror hands a message to, and what became of the message), how long a word from it keeps
// them, and how the mirrors wait between two looks for ended ones.
package splice.sessions.mirror

import splice.sessions.peer.DeliveryStatus

/** What became of a message a mirror carried on: its [status] there, or null when it is on its way. */
public data class MirrorCarried(val status: DeliveryStatus?, val detail: String? = null)

/** Carries a message a session here wrote to the mirror of [to] on to that session, from [from], the sender here. */
public fun interface MirrorCarrier {
    public suspend fun carry(to: MirroredSession, from: MirroredSession, text: String): MirrorCarried
}

/** How a machine's mirrors here reach it, by [carrier], and how long a look, an offer or a note that came from it keeps
 *  them with no other word from it. */
public data class MirrorLink(val carrier: MirrorCarrier, val lastsMs: Long)

/** How the mirrors wait between two looks for ended ones: [awaitNext] suspends for the time it is given, and answers
 *  false when the looking should stop. */
public fun interface MirrorSweep {
    public suspend fun awaitNext(intervalMs: Long): Boolean
}
