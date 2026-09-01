// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: the post-commit half of NEVER-BELOW-STATUS-QUO —
// a mid-round closed inbox surfaces as IOException specifically (TurnDriver's tearAwareEvents keys
// on `e is IOException`), and a connection is pooled only when clean AND drained.
package splice.dialect.responses

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import java.io.IOException

/**
 * The post-commit half of a round: relay the connection's inbox as a Flow until [TerminalEvent]
 * matches or the stream tears. [finishRound]'s own doc already says it was split out of
 * [roundFlow]; they stay in one file so the L3-critical pair is reviewed together.
 */
internal class WsRoundStream(
    private val log: LogSink,
    private val logKeys: WsLogKeys,
    private val pool: WsConnectionPool,
) {
    internal fun roundFlow(
        conn: WsConnection,
        key: String,
        first: JsonObject,
        isTerminal: TerminalEvent,
    ): Flow<JsonObject> {
        var completed = isTerminal(first)
        if (completed) conn.terminalSeen.set(true)
        return flow {
            emit(first)
            while (!completed) {
                // A closed inbox mid-round is a TEAR. It must surface as IOException specifically:
                // TurnDriver's pre-frame reissue keys on `e is IOException` (tearAwareEvents), and
                // the raw ClosedReceiveChannelException this used to throw matched neither that
                // check nor the translators' catch lists (adversarial review of WS-1).
                val received = conn.receiveCatching()
                val evt = received.getOrNull()
                    ?: throw IOException("websocket stream ended mid-round", received.exceptionOrNull())
                completed = isTerminal(evt)
                if (completed) conn.terminalSeen.set(true)
                emit(evt)
            }
        }.onCompletion { cause -> finishRound(conn, key, cause, completed) }
    }

    /** Pool the connection only when the round ended CLEANLY and left nothing behind; otherwise
     *  poison it. Split out of [roundFlow] to stay under the complexity ceiling. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun finishRound(conn: WsConnection, key: String, cause: Throwable?, completed: Boolean) {
        // The inbox MUST be empty to pool the connection (adversarial review of WS-3): a frame
        // the server emits AFTER the round-ending one would otherwise sit in the inbox and be
        // handed to the NEXT round as its first event — a silent cross-round frame leak, and
        // the reason this class kills rather than drains. Killing costs one reconnect (a full
        // send, i.e. today's behaviour); serving a stale frame corrupts the next turn.
        val drained = conn.inbox.isEmpty
        val poolable = cause == null && completed
        if (poolable && drained) {
            pool.release(key, conn)
        } else {
            if (poolable) {
                log(
                    "[ws] ${logKeys.logKey(key)} frames arrived after the round terminal — " +
                        "killing rather than pooling\n",
                )
            }
            // Cancelled (watchdog/client-gone) or torn: leftover frames of a half-consumed
            // round poison reuse — kill, next round reconnects (full send, status quo).
            pool.failRound(conn, key)
        }
    }
}
