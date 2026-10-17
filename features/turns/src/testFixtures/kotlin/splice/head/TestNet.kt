// NEW: OSS-M — hermetic test networking. The fixed test ports lived inside the Linux ephemeral
// range (32768-60999), so any transient outbound source port on a busy host could hold one at
// bind time (BindException at @BeforeAll — CI run 29706210520). Ports are OS-assigned now, and
// readiness is polled instead of slept for (the fixed warmup sleeps flaked on loaded hosts).
//
// 2026-09-23: the OS-assigned LEASE that lived here (freshPort) is gone. It came from that same
// ephemeral range and was free only until the lease closed (CI run 35881955038). A server a test
// constructs binds port 0 and reads back its port; a port that must be known before anything binds
// it comes from splice.core.testing.TestPorts, which reserves below the ephemeral range.
package splice.head

import java.net.Socket

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
