// NEW: (2026-09-02) the wire's vital signs for ONE socket, read at the moment it dies. Twelve
// end-of-stream lines in one day, and not one of them said WHICH socket, how old it was, whether a
// round was in flight, or when the peer last spoke — so every one was argued about from correlation
// ("25-minute cadence", "OpenAI drops everyone") instead of read. With the clauses attached, the
// same twelve read as what they are: six were sockets our own pool had left IDLE for 3-25 minutes,
// which any infrastructure reaps, and eleven killed no round at all.
//
// The clause that decides it is the ping. The peer pings every ~20s on a healthy path (probed
// live), so "last server ping 3s ago" at the end means the path was alive and the far side ended
// the response, while "last server ping 95s ago" means the path itself went dark first. Without
// that clause the honest answer is "we cannot tell", and the honest LINE must then not name an
// actor at all — see InboxListener.endOfStream for why 1006 licenses no attribution.
package splice.dialect.responses.websocket

import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import java.util.concurrent.atomic.AtomicLong

/** Timestamps a socket's listener and pool stamp as they see the wire; [describe] reads them. */
internal class WsPulse(
    private val label: String,
    private val openSockets: OpenSockets,
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) {
    private val openedAt = clock()
    private val lastFrameAt = AtomicLong(openedAt)
    private val lastPingAt = AtomicLong(NEVER)
    private val roundStartedAt = AtomicLong(NEVER)

    // V4-242: the event types the round in flight has received, for the close line. Distinct, in the
    // order they first arrived and capped, because a long round is thousands of deltas of a few types.
    // Guarded by itself: the listener writes on the socket's thread, the pool resets on the round's.
    private val roundEvents = RoundEvents()

    /** A text frame arrived (any event, terminal or not). */
    internal fun frame() {
        lastFrameAt.set(clock())
    }

    /** A server Ping arrived — the path's own liveness signal, independent of the model. */
    internal fun ping() {
        lastPingAt.set(clock())
    }

    /** Milliseconds since the last server ping, [Long.MAX_VALUE] before the first — the idle
     *  watchdog's liveness reading (WsPathPulse). */
    internal fun pingAgoMs(): Long {
        val ping = lastPingAt.get()
        return if (ping == NEVER) Long.MAX_VALUE else clock() - ping
    }

    /** V4-242: the round in flight received an event of [type]. */
    internal fun event(type: String) {
        roundEvents.add(type)
    }

    internal fun roundStarted() {
        roundEvents.clear()
        roundStartedAt.set(clock())
    }

    internal fun roundEnded() {
        roundStartedAt.set(NEVER)
        roundEvents.clear()
    }

    /** V4-242: what the round in flight had received when the socket ended, for the close line and the
     *  cause the round's tear carries; null on a socket idle between rounds, whose close ended no round.
     *  The Codex outage of 2026-09-25 closed 167 sockets right after codex.rate_limits and
     *  codex.response.metadata, and this is the clause that says so. */
    internal fun roundSoFar(): String? {
        val (count, types, more) = roundEvents.read()
        val listed = types.joinToString(", ") + if (more) ", …" else ""
        return when {
            count == 1 -> "after 1 event ($listed)"
            count > 1 -> "after $count events ($listed)"
            roundStartedAt.get() != NEVER -> "before any event of the round"
            else -> null
        }
    }

    /** One clause per fact, in the order a reader asks them: which socket, how old, was it working,
     *  when did the server last send anything, when did it last PING, how many siblings. */
    internal fun describe(): String {
        val now = clock()
        val round = roundStartedAt.get()
        val state = if (round == NEVER) "idle" else "mid-round ${secs(now - round)} in"
        val ping = lastPingAt.get()
        val pinged = if (ping == NEVER) "no server ping yet" else "last server ping ${secs(now - ping)} ago"
        return "$label age ${secs(now - openedAt)}, $state, last frame ${secs(now - lastFrameAt.get())} ago, " +
            "$pinged, open=${openSockets()}"
    }

    private fun secs(ms: Long): String = "${ms / MS_PER_S}s"

    /** The round's event count and its first [MAX_EVENT_TYPES] distinct types. */
    private class RoundEvents {
        private var count = 0
        private var more = false
        private val types = LinkedHashSet<String>()

        fun add(type: String) = synchronized(this) {
            count += 1
            if (type in types) return@synchronized
            if (types.size < MAX_EVENT_TYPES) types += type else more = true
        }

        fun clear() = synchronized(this) {
            count = 0
            more = false
            types.clear()
        }

        fun read(): Triple<Int, List<String>, Boolean> = synchronized(this) { Triple(count, types.toList(), more) }
    }
}

private const val NEVER = -1L
private const val MS_PER_S = 1000L

// Enough for a round's lifecycle and its content types; a close line is read by a person.
private const val MAX_EVENT_TYPES = 8
