// NEW: turn-path liveness probe (uptime item 1 of 2, 2026-08-12). Born from the 91-hour wedge:
// six Netty event loops spun in a corrupted LinkedHashMap, every turn request was accepted and
// never dispatched, and /health said {ok:true, readyHeads:4, failedHeads:0} the entire time —
// health reported that heads were CONFIGURED, not that the gateway could WORK. Any external
// uptime monitor pointed at it would have shown green through a total outage.
//
// The probe exercises the surface that wedged: a real loopback POST to the head's own
// /v1/messages. It carries no auth ON PURPOSE — the pipeline's quick rejection IS the liveness
// proof, because a wedged event loop returns nothing at all (measured: a 60s curl got zero bytes).
// Any HTTP status = alive; only a timeout/connection-hang counts as a failure, and two consecutive
// failures flip the head to stalled. /health then reports ok:false + turnPathStalled:[...], which
// is the flip that makes 99.9% monitorable.
//
// EXACTLY WHAT THIS PROVES, and what it does not (review 2026-08-12 — the earlier wording claimed
// more than the code does). HeadServer.handleMessages runs `authorize(call)` FIRST, so an
// unauthenticated probe is answered 401 and returns before acceptingOrRespond, the InflightGate,
// the TurnDriver, or any upstream client. So this proves the head's Netty acceptor and request
// path are RESPONSIVE — which is precisely the 91h wedge, where the event loops spun and nothing,
// 401 included, ever came back. It does NOT prove an end-to-end turn completes: a saturated or
// deadlocked gate, a HeadServer stuck draining in stopLocked, a wedged upstream client or a hung
// translator would all keep answering 401s at 30s intervals while real turns died.
//
// Going deeper was considered and REJECTED for now: an authenticated probe reaches
// acquireSlotOrRespond, so it would consume a real inflight slot every 30s and, under legitimate
// heavy load, either queue (probe times out -> false STALL, paging on a healthy-but-busy gateway)
// or 429 (an HTTP status, i.e. "alive" — no new coverage). A liveness alarm that fires during a
// traffic spike is worse than one with a documented ceiling. Deepening it needs a slot-exempt
// internal route, which is a design change, not a comment fix.
//
// Mirrors the AuthProbeLoop tick-loop idiom; the blocking HttpURLConnection rides the injected
// dispatcher, which the composition root defaults to the IO dispatcher it used to hardcode.
package splice.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.spi.ProcessDispatchers
import splice.spi.ProcessTicker
import splice.spi.Ticker
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

public class TurnPathProbeLoop(
    private val key: String,
    private val port: Int,
    private val stalled: ConcurrentHashMap<String, Boolean>,
    private val log: (String) -> Unit,
    private val intervalMs: Long = PROBE_INTERVAL_MS,
    private val timeoutMs: Int = PROBE_TIMEOUT_MS,
    // HD-19: the two runtime reaches this loop used to make directly. [dispatcher] is where the
    // blocking HttpURLConnection probe runs (was a hardcoded Dispatchers.IO); [ticker] is the tick
    // cadence (was a bare delay). Both default to the exact prior values.
    private val dispatcher: CoroutineDispatcher = ProcessDispatchers().io(),
    private val ticker: Ticker = ProcessTicker(),
) {
    private var consecutiveFailures = 0

    public fun start(scope: CoroutineScope): Job {
        val launched = scope.launch(dispatcher) {
            while (isActive) {
                // delay FIRST, deliberately: the head is still binding its port at t=0, so an
                // immediate tick (AuthProbeLoop's idiom, which has no port to wait on) would count
                // boot as a failure. The cost is a stated blind spot — no entry exists for this key
                // until the first tick, so a daemon that boots straight into a wedge serves ok:true
                // for 30s and cannot be marked stalled before 60s (two failures). A boot-time false
                // ALARM was judged worse than a 60s detection floor on a fault that has already
                // lasted hours by the time anyone looks.
                if (!ticker.awaitTick(intervalMs)) return@launch
                tick()
            }
        }
        return supervise(launched)
    }

    /** F5: FAIL TOWARD ALARM. A dead probe leaves stalled[key] frozen at its last value, and if that
     *  value was false the head reports healthy forever — the exact silent-green wedge this probe
     *  exists to kill, resurrected one level up. Supervision (not a broad tick catch) owns the
     *  unknown-throwable class here, mirroring AuthProbeLoop.launchSupervised: the repo's walls pull
     *  in opposite directions on `catch (t: Throwable)` (detekt TooGenericExceptionCaught vs the
     *  kt-catch-swallows-cancellation ast-grep rule), so the completion handler is the sanctioned
     *  seam. An alarm that cannot report is itself the alarm. Cancellation is an orderly shutdown
     *  and must NOT page. Internal so the test drives the SHIPPED handler, not a copy of it. */
    internal fun supervise(job: Job): Job {
        job.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) return@invokeOnCompletion
            stalled[key] = true
            log(
                "[$key] TURN PATH PROBE DIED ($cause) — marking the head stalled: liveness is no " +
                    "longer being measured, so /health must not keep claiming ok.\n",
            )
        }
        return job
    }

    /** One probe. Exposed for tests, which drive ticks directly instead of waiting on the loop. */
    public fun tick() {
        val alive = probeOnce()
        if (alive) {
            if (stalled[key] == true) log("[$key] turn path RECOVERED — resuming\n")
            consecutiveFailures = 0
            stalled[key] = false
        } else {
            consecutiveFailures++
            if (consecutiveFailures == STALL_THRESHOLD) {
                stalled[key] = true
                log(
                    "[$key] TURN PATH STALLED — $consecutiveFailures consecutive loopback probes " +
                        "got no response in ${timeoutMs}ms; /health now reports ok:false. " +
                        "This is the accepted-but-never-dispatched wedge signature.\n",
                )
            }
        }
    }

    /** ANY HTTP status is life; only a hang/timeout is death.
     *
     *  disconnect() is in a `finally` because the FAILURE path is the long-lived one by
     *  construction: a stalled head keeps failing every 30s, and disconnecting only on success
     *  abandoned one connection per failed probe — ~2,880/day/head, leaked by the very component
     *  that exists to protect uptime. */
    private fun probeOnce(): Boolean {
        val conn = URI("http://127.0.0.1:$port/v1/messages").toURL().openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(PROBE_BODY) }
            conn.responseCode // blocks up to readTimeout; a wedge never answers
            true
        } catch (ignored: java.io.IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }
}

// TurnPathProbeLoop's tick cadence, per-probe deadline, stall threshold, and the fixed body every
// probe POSTs. File-scope declarations (Kotlin style law, 2026-08-15): a top-level `private const
// val` / `private val` is the sanctioned home for constants, never a static block on the type.
private const val PROBE_INTERVAL_MS = 30_000L
private const val PROBE_TIMEOUT_MS = 5_000
private const val STALL_THRESHOLD = 2
private val PROBE_BODY = """{"splice_liveness_probe":true}""".toByteArray()
