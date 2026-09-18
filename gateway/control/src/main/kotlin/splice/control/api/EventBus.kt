// NEW: V4-126 FEATURES.md §6 — the console's event stream, and the whole backpressure story.
//
// The one invariant that shapes every line here: PUBLISHING MUST NEVER BLOCK A TURN. The daemon
// publishes from the turn path, so a bus that awaits a slow console would let a browser tab apply
// backpressure to the head — the console is an observer, and an observer that stops reading must
// lose events rather than stall the thing it observes. So each subscriber owns a BOUNDED channel
// that the publisher only ever `trySend`s into; an overflow is DROPPED and COUNTED, so the console
// can say "you missed 12" instead of showing a stream that quietly stopped matching reality.
//
// The event SHAPES are the contract (dev/web-console/FEATURES.md §6, owned by splice-design), so
// they are a sealed hierarchy with an explicit wire name each: the control test that pins them
// takes its denominator from these subclasses rather than from a hand-written list, which is what
// makes a NEW event type impossible to add without a disposition.
package splice.control.api

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/** How far a subscriber may fall behind before events start being dropped. Sized against the
 *  console's burst (a turn start/end pair plus head churn), not against its slowest handler: a
 *  console that cannot keep up is expected to recover from a gap, and the gap is always counted. */
private const val DEFAULT_BACKLOG = 256

/** How many recent events are kept so a reconnecting console can resume from `Last-Event-ID`. */
private const val REPLAY_RING = 512

/** One console event. [seq] is the SSE event id: monotonic, so a client that reconnects with
 *  `Last-Event-ID: n` gets everything published after n and nothing twice.
 *
 *  A sealed hierarchy rather than a `type`/`payload` pair: the compiler then refuses a subscriber
 *  that does not look at every family, and the wire name comes from the serializer descriptor
 *  instead of from a string literal at the call site (the drift class `kt-outcome-tag-single-source`
 *  exists for). */
// NOT @Serializable itself: a serializable sealed parent registers the abstract `seq` as one of its
// own serial names, and every subclass that overrides it as a constructor property then reports a
// DUPLICATE serial name for `seq` (and the same for every other shared abstract member). Only the
// subclasses are serializable, which is also what makes `encodeTo` below able to reach each one's
// own generated serializer instead of the polymorphic one.
public sealed class ConsoleEvent {
    public abstract val seq: Long

    /** The SSE `event:` name. Declared here rather than derived at the call site because main code
     *  may not reflect over the sealed subclasses; the shape test asserts this equals the
     *  serializer's own `@SerialName`, so the literal cannot drift from the wire contract.
     *
     *  A body property with no backing field, so it is not serialized into `data`. */
    public abstract val kind: String

    /** This event as its JSON `data:` object, through its OWN generated serializer — going through
     *  a polymorphic one would splice a `type` discriminator into the payload, duplicating what the
     *  `event:` field already says. */
    public abstract fun encodeTo(json: Json): String

    /** A head's liveness moved: started, stopped, or degraded to a state the console must show. */
    @Serializable
    @SerialName("head.state")
    public data class HeadState(
        override val seq: Long,
        val head: String,
        val state: String,
    ) : ConsoleEvent() {
        public override val kind: String get() = "head.state"
        public override fun encodeTo(json: Json): String = json.encodeToString(serializer(), this)
    }

    /** A turn began on a head. [session] is null for a request that carried no session header. */
    @Serializable
    @SerialName("turn.start")
    public data class TurnStart(
        override val seq: Long,
        val head: String,
        val session: String? = null,
    ) : ConsoleEvent() {
        public override val kind: String get() = "turn.start"
        public override fun encodeTo(json: Json): String = json.encodeToString(serializer(), this)
    }

    /** A turn ended. [perfRowId] is the identity of the perf row the console joins against, so a
     *  click on the stream lands on the same row the poll route would have returned. */
    @Serializable
    @SerialName("turn.end")
    public data class TurnEnd(
        override val seq: Long,
        val head: String,
        val perfRowId: String,
        val outcome: String,
    ) : ConsoleEvent() {
        public override val kind: String get() = "turn.end"
        public override fun encodeTo(json: Json): String = json.encodeToString(serializer(), this)
    }

    /** The session registry changed: a session appeared, moved head, or was retired. */
    @Serializable
    @SerialName("session.change")
    public data class SessionChange(
        override val seq: Long,
        val session: String,
        val head: String,
    ) : ConsoleEvent() {
        public override val kind: String get() = "session.change"
        public override fun encodeTo(json: Json): String = json.encodeToString(serializer(), this)
    }

    /** A message edge was observed on the wire. Metadata only, never text: the console reads the
     *  text from the transcript route when the operator opens the edge. */
    @Serializable
    @SerialName("message.edge")
    public data class EdgeEvent(
        override val seq: Long,
        val from: String,
        val to: String,
        val at: Long,
    ) : ConsoleEvent() {
        public override val kind: String get() = "message.edge"
        public override fun encodeTo(json: Json): String = json.encodeToString(serializer(), this)
    }

    /** The pool switched which account a session rides. */
    @Serializable
    @SerialName("account.switch")
    public data class AccountSwitched(
        override val seq: Long,
        val head: String,
        val from: String? = null,
        val to: String,
    ) : ConsoleEvent() {
        public override val kind: String get() = "account.switch"
        public override fun encodeTo(json: Json): String = json.encodeToString(serializer(), this)
    }
}

/** One subscriber's view of the bus: a bounded channel the publisher fills without ever waiting,
 *  plus the count of what it had to drop to keep that promise. */
internal class EventSubscription internal constructor(private val capacity: Int) {
    internal val channel: Channel<ConsoleEvent> = Channel(capacity)
    private val droppedCount = AtomicLong(0)

    /** The events this subscriber missed because it was not draining. NOT put on the wire: the
     *  console's contract is id/event/data plus a heartbeat comment, and inventing a fourth frame
     *  here would be a contract change asked of splice-design, not taken. The count exists so the
     *  gap is observable rather than silent — a console that fell behind re-reads the poll route,
     *  which is exactly why the poll routes stay untouched. */
    internal val dropped: Long get() = droppedCount.get()

    /** Publisher side. Returns false when the event was dropped rather than queued. */
    internal fun offer(event: ConsoleEvent): Boolean {
        if (channel.trySend(event).isSuccess) return true
        droppedCount.incrementAndGet()
        return false
    }

    internal fun close() {
        channel.close()
    }
}

/** Fan-out with a replay ring. Every method that touches [subscribers] or the ring holds [lock];
 *  publishing never suspends, so the lock is only ever held for the length of a trySend loop. */
public class EventBus(private val backlog: Int = DEFAULT_BACKLOG) {
    private val lock = Any()
    private val subscribers = mutableSetOf<EventSubscription>()
    private val ring = ArrayDeque<ConsoleEvent>(REPLAY_RING)
    private val nextSeq = AtomicLong(1)

    /** Publishes to every subscriber. NEVER blocks: a full subscriber drops and counts.
     *
     *  PUBLIC, and the only thing :app gets: the daemon publishes from the turn path, so this is the
     *  one method that crosses the module boundary. `subscribe` and `unsubscribe` stay internal
     *  because only the route subscribes, which keeps a subscriber handle out of the public surface
     *  where nothing could do anything useful with it anyway. */
    public fun publish(build: (Long) -> ConsoleEvent): ConsoleEvent {
        val event = build(nextSeq.getAndIncrement())
        synchronized(lock) {
            ring.addLast(event)
            while (ring.size > REPLAY_RING) ring.removeFirst()
            subscribers.forEach { it.offer(event) }
        }
        return event
    }

    /** Joins the stream. [lastEventId] replays what the client missed; null starts from now, which
     *  is the honest answer for a console that has never seen this stream. An id the ring can no
     *  longer cover replays everything it holds — the client is told it is behind by the seq of the
     *  first event it receives, never silently given a hole. */
    internal fun subscribe(lastEventId: Long? = null): EventSubscription {
        val subscription = EventSubscription(backlog)
        synchronized(lock) {
            subscribers.add(subscription)
            if (lastEventId != null) {
                ring.filter { it.seq > lastEventId }.forEach { subscription.offer(it) }
            }
        }
        return subscription
    }

    internal fun unsubscribe(subscription: EventSubscription) {
        synchronized(lock) { subscribers.remove(subscription) }
        subscription.close()
    }

    /** How many subscribers currently hold the stream open. */
    internal val subscriberCount: Int get() = synchronized(lock) { subscribers.size }
}
