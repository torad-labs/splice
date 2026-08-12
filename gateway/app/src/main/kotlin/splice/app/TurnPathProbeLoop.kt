// NEW: turn-path liveness probe (uptime item 1 of 2, 2026-08-12). Born from the 91-hour wedge:
// six Netty event loops spun in a corrupted LinkedHashMap, every turn request was accepted and
// never dispatched, and /health said {ok:true, readyHeads:4, failedHeads:0} the entire time —
// health reported that heads were CONFIGURED, not that the gateway could WORK. Any external
// uptime monitor pointed at it would have shown green through a total outage.
//
// The probe exercises the exact surface that wedged: a real loopback POST to the head's own
// /v1/messages. It carries no auth and a garbage body ON PURPOSE — the pipeline's quick rejection
// (401/400) IS the liveness proof, because a wedged event loop returns nothing at all (measured:
// a 60s curl got zero bytes). Any HTTP status = alive; only a timeout/connection-hang counts as
// a failure, and two consecutive failures flip the head to stalled. /health then reports
// ok:false + turnPathStalled:[...], which is the flip that makes 99.9% monitorable.
//
// Mirrors the AuthProbeLoop delay-loop idiom; blocking HttpURLConnection rides Dispatchers.IO.
package splice.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
) {
    private var consecutiveFailures = 0

    public fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(intervalMs)
            tick()
        }
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

    /** ANY HTTP status is life; only a hang/timeout is death. */
    private fun probeOnce(): Boolean = try {
        val conn = URI("http://127.0.0.1:$port/v1/messages").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(PROBE_BODY) }
        conn.responseCode // blocks up to readTimeout; a wedge never answers
        conn.disconnect()
        true
    } catch (ignored: java.io.IOException) {
        false
    }

    private companion object {
        const val PROBE_INTERVAL_MS = 30_000L
        const val PROBE_TIMEOUT_MS = 5_000
        const val STALL_THRESHOLD = 2
        val PROBE_BODY = """{"splice_liveness_probe":true}""".toByteArray()
    }
}
