// NEW 2026-11-19 (reply-stream): the daemon's one hub of live replies, keyed by the client's session id. Every head is
// handed it (HeadServerFactory, beside its live turns) and taps its turns' client frames into it; the control plane's
// GET /api/sessions/{id}/live follows one session through it. A session nobody follows costs its turns one lock and
// one held reference per frame: no parsing, no copying.
package splice.head.live

import splice.core.memory.HeapReservations
import splice.core.util.WallClock
import splice.head.turn.Preparation
import splice.upstream.memory.JvmHeap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Every session's live reply, as its followers read it. Read-only from outside: nothing reaches a turn through it. */
public class LiveReplies(
    private val heap: HeapReservations = JvmHeap.budget,
    private val wall: WallClock = WallClock(System::currentTimeMillis),
) {
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val minted = AtomicLong()
    private val forget = SessionForget { sessions.remove(it.id, it) }

    /** A turn of [facts]' session the head admitted now. It shows to followers once its response begins. */
    internal fun begin(facts: LiveTurnFacts): LiveReply =
        LiveReply(this, "turn-${minted.incrementAndGet()}", facts, wall(), HeldFrames(heap))

    /** [prepared], a turn [head] is about to drive, as its session's followers will see it; none for a turn that
     *  names no session. */
    internal fun begin(prepared: Preparation.Ready, head: String): LiveReply? {
        val meta = prepared.built.meta
        val session = meta.scope.sessionId ?: return null
        return begin(
            LiveTurnFacts(
                session = session,
                agent = prepared.harness?.agentId,
                // a door turn asks for the provider's own id; the door names what Claude Code asked for
                model = meta.route.relay.askedModel ?: meta.route.originalModel,
                head = head,
                compact = meta.compact,
            ),
        )
    }

    /** Follow [session]: its `hello` and snapshot at once, then every event of its turns. [overflow] is told when the
     *  follower falls [FOLLOWER_QUEUE_EVENTS] behind; its events then close. A session never seen is no error. */
    public fun follow(session: String, overflow: FollowerOverflow): LiveFollow =
        withSession(session) { it.join(LiveFollower(overflow)) }

    internal fun started(reply: LiveReply): LiveSession = withSession(reply.facts.session) {
        it.started(reply)
        it
    }

    /** A non-stream turn's answer, for its session's followers; a session nobody follows reads nothing. */
    internal fun answered(reply: LiveReply, answer: LiveAnswer) {
        while (true) {
            val session = sessions[reply.facts.session] ?: return
            synchronized(session) {
                if (!session.retired) {
                    session.answered(reply, answer)
                    return
                }
            }
        }
    }

    /** Runs [block] under [id]'s session lock, making the session when there is none, and forgets it after if nothing
     *  is left on it. A session forgotten between the lookup and the lock is looked up again. */
    private inline fun <T> withSession(id: String, block: (LiveSession) -> T): T {
        while (true) {
            val session = sessions.computeIfAbsent(id) { LiveSession(it, forget) }
            synchronized(session) {
                if (!session.retired) {
                    val result = block(session)
                    session.retireIfIdle()
                    return result
                }
            }
        }
    }
}
