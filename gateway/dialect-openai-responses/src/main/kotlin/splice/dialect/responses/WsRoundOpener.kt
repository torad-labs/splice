// PORT-OF: WsUpstream.kt @ 81ff23c — invariants: the NEVER-BELOW-STATUS-QUO pre-commit contract —
// every path through sendFrame/awaitFirstEvent either returns a value or returns null after
// pool.failRound, and neither may ever throw (cancellation excepted, and it must propagate).
package splice.dialect.responses

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import java.io.IOException

/**
 * The pre-commit half of a round: send the one request frame and wait for the first event to
 * arrive. Every exit before the commit point is `null` — the caller falls back to SSE — and the
 * connection is poisoned on any failure so the next round reconnects instead of reusing bad state.
 */
internal class WsRoundOpener(
    private val log: LogSink,
    private val logKeys: WsLogKeys,
    private val firstEventTimeoutMs: Long,
    private val pool: WsConnectionPool,
) {
    /** Send the round's one frame and AWAIT delivery.
     *
     *  Both failure modes are real and neither was caught before the adversarial review of WS-1:
     *  sendText returns a CompletableFuture (javap: `CompletableFuture<WebSocket> sendText(...)`),
     *  so a delivery failure is ASYNCHRONOUS and was previously discarded with the future; and its
     *  SYNCHRONOUS throw is IllegalStateException ("Send pending"/output closed), which
     *  runCatchingCancellable deliberately cannot catch because CancellationException extends
     *  IllegalStateException — catching ISE there would swallow cancellation repo-wide.
     *  Hence the explicit catch with the cancellation rethrow, and the await. */
    internal suspend fun sendFrame(conn: WsConnection, key: String, frame: String): Boolean {
        // "sync" vs "async" stays in the log line on purpose: they are different upstream faults
        // (a socket we already broke vs a delivery the peer refused) and only the log distinguishes
        // them after the fact.
        val failure: Pair<String, Throwable>? = try {
            conn.socket.sendText(frame, true).await()
            null
        } catch (e: CancellationException) {
            throw e // a cancelled turn must stop, never look like a send failure
        } catch (e: IllegalStateException) {
            "sync" to e // output closed, or a send already pending on this socket
        } catch (e: IOException) {
            "async" to e // the delivery future completed exceptionally
        }
        if (failure != null) {
            val (kind, error) = failure
            log(
                "[ws] ${logKeys.logKey(key)} send failed $kind (${error::class.simpleName}: " +
                    "${error.message?.take(ERR_SNIPPET)}) — killing connection, round rides SSE\n",
            )
            pool.failRound(conn, key)
        }
        return failure == null
    }

    /** The commit point: no event within the budget → the round (and the connection, whose state
     *  is now indeterminate — the server may or may not have started the response) goes to SSE.
     *  A CLOSED inbox ends the wait IMMEDIATELY instead of burning the whole budget, and says so
     *  distinguishably: "inbox closed before first event" and "no first event in Nms" are different
     *  upstream faults, and daemon.log is the only place that difference survives. */
    internal suspend fun awaitFirstEvent(conn: WsConnection, key: String): JsonObject? {
        val received = withTimeoutOrNull(firstEventTimeoutMs) { conn.receiveCatching() }
        val first = received?.getOrNull()
        if (first == null) {
            val why = if (received == null) {
                "no first event in ${firstEventTimeoutMs}ms"
            } else {
                "inbox closed before first event (${received.exceptionOrNull()?.message?.take(ERR_SNIPPET) ?: "clean"})"
            }
            log("[ws] ${logKeys.logKey(key)} $why — killing connection, round rides SSE\n")
            pool.failRound(conn, key)
        }
        return first
    }
}
