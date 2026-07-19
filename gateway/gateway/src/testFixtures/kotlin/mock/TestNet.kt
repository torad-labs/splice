// NEW: OSS-M — hermetic test networking. The fixed test ports lived inside the Linux ephemeral
// range (32768-60999), so any transient outbound source port on a busy host could hold one at
// bind time (BindException at @BeforeAll — CI run 29706210520). Ports are OS-assigned now, and
// readiness is polled instead of slept for (the fixed warmup sleeps flaked on loaded hosts).
package mock

import java.net.ServerSocket
import java.net.Socket

/** An OS-assigned free port. Closed before use — the tiny reuse race is fine for tests. */
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
