// PORT-OF: splice/gateway/head/TurnDriver.kt (ClientChannel, TurnWiring.timedClientWrite,
// TurnDriver.launchClientPinger, the DELTA/START/PING frame prefixes, SSE_KEEPALIVE_COMMENT,
// CLIENT_PING_INTERVAL_MS) @ 86f1411 — invariants unchanged: the per-turn client write surface,
// moved to splice.gateway.wire (HD-24) — the package that already owns ImmediateSseWriter,
// SseEmitter, TurnTerminal, CollectingTerminal. timedClientWrite and launchClientPinger are one
// responsibility, not two: timedClientWrite flips clientGone on a failed frame write and the
// pinger flips it on a failed keepalive write — the same mechanism on the same three fields this
// data class holds, so both became member functions instead of free functions taking the fields as
// separate arguments.
package splice.gateway.wire

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.spi.Ticker
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// first_delta detection reads the frame prefix — the emitter's event name, not a literal
// stop-reason (L3 walls stay intact; this only OBSERVES the already-built frame).
private const val DELTA_FRAME_PREFIX = "event: content_block_delta"
private const val START_FRAME_PREFIX = "event: message_start"
private const val PING_FRAME_PREFIX = "event: ping"

// SSE comment keepalive: pure transport, invisible to SSE parsers (spec: lines starting
// with ':' are comments) — exists ONLY so a dead client fails a write promptly.
private const val SSE_KEEPALIVE_COMMENT = ": ping\n\n"

// HEAD-008: 10s left a dead-without-FIN client (and the paid upstream stream + inflight
// slot behind it) undetected for up to 10s; tightened to 2s. Same mechanism, smaller tick.
private const val CLIENT_PING_INTERVAL_MS = 2_000L

/** Per-turn client write surface: the coalesced writer, a mutex serializing the emitter vs the
 *  keepalive pinger, and the clientGone flag a failed write flips. */
internal data class ClientChannel(
    val coalesced: ImmediateSseWriter,
    val writeMutex: Mutex,
    val clientGone: AtomicBoolean,
) {
    /** Client-side write instrumented: frame counts/bytes, first-frame/first-delta marks, and the
     *  summed write+flush time (a slow reader shows up as write_ms, not as fake stream time).
     *  A failed write flips [clientGone] BEFORE rethrowing — the translator's terminal decision
     *  reads it to classify the ending as ClientAbandoned instead of upstream truncation. The
     *  caller holds [writeMutex] around this call; it does not lock itself. */
    fun timedClientWrite(frame: String, perf: TurnPerf, clock: ElapsedClock) {
        val t = clock()
        try {
            coalesced.write(frame)
        } catch (e: IOException) {
            clientGone.set(true)
            throw e
        }
        perf.add(PerfKeys.WRITE_MS, clock() - t)
        perf.add(PerfKeys.FRAMES_OUT, 1)
        // Structural opener carries no content — see PerfKeys.CONTENT_FRAMES_OUT for why G5 must not
        // count it as "the client saw output".
        if (!frame.startsWith(START_FRAME_PREFIX) && !frame.startsWith(PING_FRAME_PREFIX)) {
            perf.add(PerfKeys.CONTENT_FRAMES_OUT, 1)
        }
        perf.add(PerfKeys.BYTES_OUT, frame.length.toLong())
        perf.markOnce(PerfKeys.FIRST_FRAME)
        if (frame.startsWith(DELTA_FRAME_PREFIX)) perf.markOnce(PerfKeys.FIRST_DELTA)
    }

    // Client-liveness pinger: an SSE COMMENT (spec-legal, ignored by every parser) every interval.
    // With NO downstream writes flowing (headers-wait on a long prefill, retry backoff, thinking
    // pause) a dead client is otherwise invisible — the disconnect load test measured slots pinned
    // for the whole watchdog budget, and the 2026-07-19 429 storm stacked ~650 zombie turns whose
    // clients had re-sent minutes earlier. Whole-turn scope (driveOneTurn), NOT per-attempt.
    // A failed ping flips clientGone and cancels just the turn.
    // HD-20: the CoroutineScope receiver became [scope], the first parameter. The scope passed at
    // the call site is still driveOneTurn's withContext(turnJob) scope — the pinger's whole-turn
    // lifetime and cancellation parentage are unchanged.
    /** Collect-path liveness sink (HD-29): flip [clientGone] and cancel the turn. No bytes
     *  written — the stream path's failed write/ping is the sibling; this is what a
     *  connection-closed signal (Netty closeFuture) calls. */
    fun connectionClosed(turnJob: Job) {
        clientGone.set(true)
        turnJob.cancel()
    }

    fun launchClientPinger(scope: CoroutineScope, turnJob: Job, ticker: Ticker, headKey: String, log: LogSink): Job =
        scope.launch {
            while (isActive) {
                // HD-19: the ping cadence is a named Ticker, not a bare delay. ProcessTicker always
                // returns true, so this loop is exactly as unbounded as before; a test can wire a
                // ticker that paces N pings instantly and then stops the loop.
                if (!ticker.awaitTick(CLIENT_PING_INTERVAL_MS)) return@launch
                try {
                    writeMutex.withLock { coalesced.write(SSE_KEEPALIVE_COMMENT) }
                } catch (e: IOException) {
                    clientGone.set(true)
                    log("[$headKey] client gone (keepalive write failed: ${e.message}) — cancelling turn\n")
                    turnJob.cancel()
                    return@launch
                }
            }
        }
}
