// NEW 2026-11-19 (reply-stream): one follower of a session's live reply: a bounded queue the turns offer events to
// without ever waiting, and what the route reads it through.
package splice.head.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** How many events a follower may fall behind before its response is ended. A snapshot counts one event per block,
 *  so joining never fills it by itself. */
internal const val FOLLOWER_QUEUE_EVENTS = 4096

/** The comment a quiet live stream gets, once [LIVE_PING_AFTER_MS] have passed with nothing written to it. */
internal const val LIVE_PING: String = ": ping\n\n"

/** How long a live stream stays quiet before it gets [LIVE_PING]. */
internal const val LIVE_PING_AFTER_MS: Long = 15_000L

/** What a follower whose queue overflowed asks of its writer: stop, and end the response without draining. */
public fun interface FollowerOverflow {
    public fun overflowed()
}

/** A follower's queue. [offer] never suspends: a full queue drops the follower ([drop]), so a reader that falls
 *  behind never slows the turn that writes. */
internal class LiveFollower(private val overflow: FollowerOverflow) {
    val queue: Channel<String> = Channel(FOLLOWER_QUEUE_EVENTS)

    fun offer(event: String): Boolean = queue.trySend(event).isSuccess

    /** No further event: what is queued is discarded and the writer is told to end the response. */
    fun drop() {
        queue.cancel()
        overflow.overflowed()
    }
}

/** One follower's view of a session, as the route writes it: [hello] first, then every event of [events] as it
 *  comes, until [events] closes (the follower fell too far behind) or the client leaves. [leave] when done. */
public class LiveFollow internal constructor(
    /** The `hello` event, already on the wire: the session and its turns in flight when the follower joined. */
    public val hello: String,
    private val follower: LiveFollower,
    private val session: LiveSession,
) {
    /** Each event after [hello], already on the wire, the joining snapshot first. */
    public val events: ReceiveChannel<String> get() = follower.queue

    /** The follower stops following: the session forgets it. Safe to call more than once. */
    public fun leave() {
        session.leave(follower)
    }
}
