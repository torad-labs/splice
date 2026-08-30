// 2026-08-12: the liveness half of the uptime work. The 91h wedge's signature was "accepted but
// never dispatched": connections opened, zero bytes ever came back, while /health stayed green.
// These tests drive the probe against both shapes — a server that ANSWERS (even with an error
// status: a 400 is proof of life) and a server that ACCEPTS-THEN-HANGS (the wedge) — and pin
// that only the second flips the stalled flag, after exactly the threshold, with recovery back.
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.TurnPathProbeLoop
import splice.core.util.Cancellables
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class TurnPathProbeLoopTest {

    private val sockets = mutableListOf<ServerSocket>()
    private val logs = mutableListOf<String>()

    @AfterEach
    fun tearDown() = sockets.forEach {
        Cancellables.discard(runCatching { it.close() }, "turn-probe test server teardown")
    }

    /** A server that answers every request with an HTTP error — alive, just unhappy. */
    private fun answeringServer(): Int {
        val ss = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress()).also(sockets::add)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                Cancellables.discard(
                    runCatching {
                        val c = ss.accept()
                        thread(isDaemon = true) {
                            Cancellables.discard(
                                runCatching {
                                    c.getInputStream().read(ByteArray(1024)) // drain a little
                                    c.getOutputStream().write(
                                        "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                                    )
                                    c.close()
                                },
                                "turn-probe test connection teardown",
                            )
                        }.name = "turn-probe-answer"
                    },
                    "turn-probe test accept loop",
                )
            }
        }.name = "turn-probe-answer-accept"
        return ss.localPort
    }

    /** The wedge: accepts the connection and never writes a byte. */
    private fun hangingServer(): Int {
        val ss = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress()).also(sockets::add)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                Cancellables.discard(
                    runCatching { ss.accept() }, // hold it open, say nothing
                    "turn-probe hanging test accept loop",
                )
            }
        }.name = "turn-probe-hang-accept"
        return ss.localPort
    }

    private fun probe(port: Int, stalled: ConcurrentHashMap<String, Boolean>) =
        TurnPathProbeLoop("t", port, stalled, { logs.add(it) }, intervalMs = 10, timeoutMs = 300)

    @Test
    fun `an error response is proof of life - never stalls`() {
        val stalled = ConcurrentHashMap<String, Boolean>()
        val p = probe(answeringServer(), stalled)
        repeat(4) { p.tick() }
        assertFalse(stalled["t"] == true, "a 400 is a live turn path")
        assertTrue(logs.none { "STALLED" in it })
    }

    @Test
    fun `accepted-but-silent flips stalled at exactly the threshold`() {
        val stalled = ConcurrentHashMap<String, Boolean>()
        val p = probe(hangingServer(), stalled)
        p.tick()
        assertFalse(stalled["t"] == true, "one failure must not stall (transient tolerance)")
        p.tick()
        assertTrue(stalled["t"] == true, "two consecutive hangs are the wedge signature")
        assertEquals(1, logs.count { "TURN PATH STALLED" in it }, "the transition logs once, not per tick")
    }

    @Test
    fun `recovery flips back and logs the transition`() {
        val stalled = ConcurrentHashMap<String, Boolean>()
        val hung = hangingServer()
        val p = probe(hung, stalled)
        repeat(2) { p.tick() }
        assertTrue(stalled["t"] == true)
        // same key, now against a live server — a restart/recovery in place
        val p2 = probe(answeringServer(), stalled)
        p2.tick()
        assertFalse(stalled["t"] == true, "recovery must clear the flag")
        assertTrue(logs.any { "RECOVERED" in it })
    }

    @Test
    fun `nothing listening counts as failure - connection refused is not life`() {
        val dead = ServerSocket(0).let {
            val p = it.localPort
            it.close()
            p
        }
        val stalled = ConcurrentHashMap<String, Boolean>()
        val p = probe(dead, stalled)
        repeat(2) { p.tick() }
        assertTrue(stalled["t"] == true)
    }

    // F5 (review 2026-08-12): an alarm that cannot report must itself read as an alarm. The probe
    // loop dying left stalled[key] frozen at its last value — false, in the overwhelmingly common
    // case where the head was healthy right up until the probe broke — so /health kept serving
    // ok:true with liveness no longer being measured at all. That is precisely the silent-green
    // wedge the probe was built to kill, resurrected one level up.
    @Test
    fun `a probe loop that dies abnormally marks the head stalled`() = runBlocking {
        val stalled = ConcurrentHashMap<String, Boolean>()
        stalled["t"] = false // healthy right up until the probe breaks — the dangerous case
        val boom = RuntimeException("probe internals exploded")
        val scope = CoroutineScope(Job())
        // A loop whose body throws a non-cancellation error, supervised by the SAME completion
        // handler start() installs. Driven through the real class so the handler under test is
        // the shipped one.
        val loop = TurnPathProbeLoop("t", port = 1, stalled = stalled, log = { logs += it }, intervalMs = 1)
        val job = loop.start(scope)
        job.cancel() // clean shutdown first: cancellation must NOT page
        job.join()
        assertFalse(stalled["t"] == true, "a cancelled probe is an orderly shutdown, not an outage")

        // Now the abnormal death, through the same public seam.
        val dying = scope.launch { throw boom }
        loop.supervise(dying)
        dying.join()
        assertTrue(stalled["t"] == true, "a dead probe must fail toward alarm, not freeze healthy")
        assertTrue(logs.any { "PROBE DIED" in it }, "the death must be loud in the log")
    }
}
