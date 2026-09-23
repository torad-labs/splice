// NEW: OSS-M — hermetic test networking. The fixed test ports lived inside the Linux ephemeral
// range (32768-60999), so any transient outbound source port on a busy host could hold one at
// bind time (BindException at @BeforeAll — CI run 29706210520). Ports are OS-assigned now, and
// readiness is polled instead of slept for (the fixed warmup sleeps flaked on loaded hosts).
package splice.head

import java.net.ServerSocket
import java.net.Socket

/** An OS-assigned port, LEASED: the socket is closed before anyone binds the number, and anything
 *  may take it in that window (CI run 35881955038: HeadServerLoadTest's head lost its leased port
 *  and failed the bind in @BeforeAll). The race is not fine, so a test that constructs a HeadServer
 *  or ControlServer passes port 0 and reads `head.port` / `listeningPort` after start instead.
 *
 *  What remains are the full-daemon boots in :app, whose ports go into a topology the daemon
 *  renders into launch specs, the statusline command and the MCP endpoint BEFORE it binds, and
 *  whose heads reject port 0 by design (Topology.invalidPortHeads, CTL-005). They keep this lease
 *  and its window until the daemon can bind first and advertise after. */
fun freshPort(): Int = ServerSocket(0).use { it.localPort }

/** Block until something accepts on every port (loopback), or fail loudly after the timeout. */
fun awaitListening(vararg ports: Int, timeoutMs: Long = 10_000) {
    for (p in ports) awaitOne(p, timeoutMs)
}

private fun awaitOne(port: Int, timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
        check(System.currentTimeMillis() < deadline) { "nothing listening on :$port within ${timeoutMs}ms" }
        Thread.sleep(50)
    }
}
