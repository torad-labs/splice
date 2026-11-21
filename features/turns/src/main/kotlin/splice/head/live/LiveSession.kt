// NEW 2026-11-19 (reply-stream): one session as its followers see it: who follows it and which of its turns are in
// flight. Every change happens under this object's lock, the one a turn appends its frames under, so a follower that
// joins takes its snapshot and is registered with no frame falling between the two or landing in both.
package splice.head.live

/** What the hub does with a session nobody follows and no turn is in flight on: forgets it. */
internal fun interface SessionForget {
    fun forget(session: LiveSession)
}

/** One session's followers and its turns in flight, in the order they started. Every member that changes them runs
 *  under this object's lock: [written], [cut] and [leave] take it themselves, and the hub holds it around the rest. */
internal class LiveSession(val id: String, private val forget: SessionForget) {
    /** Set once the hub has forgotten this session; a hub that finds it set makes a fresh one. */
    var retired: Boolean = false
        private set

    private val followers = ArrayList<LiveFollower>()
    private val turns = LinkedHashMap<String, LiveReply>()

    /** A follower joins: its snapshot of each turn in flight goes into its queue, in the order the turns started, and
     *  it gets every event from here on. A turn whose frames already hold its end is no longer in flight. Under the
     *  lock (the hub's). */
    fun join(follower: LiveFollower): LiveFollow {
        followers += follower
        val snapshot = ArrayList<String>()
        for (reply in turns.values.toList()) {
            val events = reply.snapshot()
            if (events == null) finish(reply) else snapshot += events
        }
        val hello = LiveWire.hello(id, turns.keys.toList())
        snapshot.forEach(follower::offer)
        return LiveFollow(hello, follower, this)
    }

    fun leave(follower: LiveFollower): Unit = synchronized(this) {
        followers.remove(follower)
        retireIfIdle()
    }

    /** [reply]'s response began reaching its client. Under the lock (the hub's). */
    fun started(reply: LiveReply) {
        turns[reply.id] = reply
        if (followers.isNotEmpty()) publish(listOf(reply.opening()))
    }

    /** [frame] was written toward [reply]'s client. With nobody following, it is only held for a later joiner. */
    fun written(reply: LiveReply, frame: String): Unit = synchronized(this) {
        if (turns[reply.id] !== reply) return
        reply.hold(frame)
        if (followers.isEmpty()) return
        publish(reply.follow(frame))
        if (reply.ended) finish(reply)
    }

    /** [reply] ended without its client being sent a stop or an error: its followers get its `end` as cut. */
    fun cut(reply: LiveReply): Unit = synchronized(this) {
        if (turns[reply.id] !== reply) return
        if (followers.isNotEmpty()) publish(listOf(reply.cutEnding()))
        finish(reply)
    }

    /** A non-stream turn's whole answer, for whoever follows now; it is never in flight. Under the lock (the hub's). */
    fun answered(reply: LiveReply, answer: LiveAnswer) {
        if (followers.isNotEmpty()) publish(reply.answered(answer))
    }

    /** Forgotten by the hub once nobody follows and nothing is in flight. Under the lock. */
    fun retireIfIdle() {
        val inUse = followers.isNotEmpty() || turns.isNotEmpty()
        if (retired || inUse) return
        retired = true
        forget.forget(this)
    }

    private fun finish(reply: LiveReply) {
        turns.remove(reply.id)
        reply.release()
        retireIfIdle()
    }

    /** Every follower gets [events], in order. One whose queue is full is dropped: its response ends, and it can
     *  connect again for the snapshot. */
    private fun publish(events: List<String>) {
        if (events.isEmpty()) return
        val behind = followers.filterNot { follower -> events.all(follower::offer) }
        if (behind.isEmpty()) return
        // Forgotten first, then told: ending a follower's writer may run its cleanup on this very thread.
        followers.removeAll(behind.toSet())
        behind.forEach(LiveFollower::drop)
    }
}
