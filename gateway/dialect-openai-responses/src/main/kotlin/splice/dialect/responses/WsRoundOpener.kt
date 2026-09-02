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
    /** No default: the transport's budgets all live at WsUpstream's file scope, where SEND_TIMEOUT_MS
     *  is private, and only WsUpstream constructs this. */
    private val sendTimeoutMs: Long,
) {
    /** Send the round's one frame and AWAIT delivery, BOUNDED.
     *
     *  Both throwing modes are real and neither was caught before the adversarial review of WS-1:
     *  sendText returns a CompletableFuture (javap: `CompletableFuture<WebSocket> sendText(...)`),
     *  so a delivery failure is ASYNCHRONOUS and was previously discarded with the future; and its
     *  SYNCHRONOUS throw is IllegalStateException ("Send pending"/output closed), which
     *  runCatchingCancellable deliberately cannot catch because CancellationException extends
     *  IllegalStateException — catching ISE there would swallow cancellation repo-wide.
     *  Hence the explicit catch with the cancellation rethrow, and the await.
     *
     *  DR-182 adds the third mode, which is a STALL rather than a throw. The future completes when
     *  the write reaches the transport, so a peer that stops reading — zero window, a black-holed
     *  connection — leaves it pending with nothing thrown, forever. That mattered more than it
     *  looks: [awaitFirstEvent]'s budget is applied AFTER this returns, so a stalled send never
     *  started the 15s clock, and WsRoundDriver's comment on this exact window ("owned by the
     *  transport's own first-event timeout, which ends it with a null and rides SSE") was true of
     *  connect and of the first event but not of the send. The only surviving bound was the
     *  whole-turn totalCap poller, so instead of degrading to SSE in seconds the round burned the
     *  entire upstream timeout and then failed the turn — below the status quo the comment
     *  promised. Bounded here, a stall is just another send failure: poison, log, ride SSE. */
    internal suspend fun sendFrame(conn: WsConnection, key: String, frame: String): Boolean {
        // "sync" / "async" / "stalled" stay in the log line on purpose: they are different upstream
        // faults (a socket we already broke, a delivery the peer refused, a peer that took the
        // frame and stopped reading) and only the log distinguishes them after the fact.
        val failure: Pair<String, Throwable?>? = try {
            val delivered = withTimeoutOrNull(sendTimeoutMs) { conn.socket.sendText(frame, true).await() }
            if (delivered == null) "stalled" to null else null
        } catch (e: CancellationException) {
            throw e // a cancelled turn must stop, never look like a send failure
        } catch (e: IllegalStateException) {
            "sync" to e // output closed, or a send already pending on this socket
        } catch (e: IOException) {
            "async" to e // the delivery future completed exceptionally
        }
        if (failure != null) {
            val (kind, error) = failure
            val detail = if (error == null) {
                "no delivery in ${sendTimeoutMs}ms"
            } else {
                "${error::class.simpleName}: ${error.message?.take(ERR_SNIPPET)}"
            }
            log("[ws] ${logKeys.logKey(key)} send failed $kind ($detail) — killing connection, round rides SSE\n")
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
