// NEW: (2026-09-02) the wire's vital signs for ONE socket, read at the moment it dies. Twelve
// "socket closed by the server (status=1006)" lines in one day, and not one of them said WHICH
// socket, how old it was, whether a round was in flight, or when the server last spoke — so every
// close was argued about from correlation ("25-minute cadence", "OpenAI drops everyone") instead
// of read. status=1006 is the JDK's word for a TCP end with no close frame (WebSocketImpl:
// onComplete -> signalClose(CLOSED_ABNORMALLY)); it names the shape, never the cause. The cause
// is in the seconds before it: the server pings every ~20s on a healthy path (probed live), so
// "last server ping 3s ago" at the close means the path was alive and the ORIGIN ended the
// response, while "last server ping 95s ago" means the path itself went dark first. That one
// clause is the difference between a retry policy and a fix, and it is now on every close line.
package splice.dialect.responses

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

    /** A text frame arrived (any event, terminal or not). */
    internal fun frame() {
        lastFrameAt.set(clock())
    }

    /** A server Ping arrived — the path's own liveness signal, independent of the model. */
    internal fun ping() {
        lastPingAt.set(clock())
    }

    internal fun roundStarted() {
        roundStartedAt.set(clock())
    }

    internal fun roundEnded() {
        roundStartedAt.set(NEVER)
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
}

private const val NEVER = -1L
private const val MS_PER_S = 1000L
