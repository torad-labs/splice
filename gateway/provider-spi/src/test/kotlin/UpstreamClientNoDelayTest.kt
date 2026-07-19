// G26: TCP_NODELAY on the upstream client hop cannot be verified via any public JDK HttpClient
// API — java.net.http.HttpClient/Builder expose no reader/setter for it (JDK-8338681, open).
// defaultClient() logs that fact once per guard instance instead of pretending to verify it.
// UpstreamClientConnectTimeoutTest ALSO calls defaultClient() directly (a real-socket connect-
// timeout probe, unrelated to logging) — sharing the production companion-object guard across
// test classes would make "exactly once" order-dependent on which test class the JUnit engine
// happens to run first, so this test pins its own fresh AtomicBoolean via the noDelayGuard
// parameter rather than relying on the process-wide default.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.spi.UpstreamClient
import java.util.concurrent.atomic.AtomicBoolean

class UpstreamClientNoDelayTest {

    @Test
    fun `client tcp_nodelay diagnostic logs exactly once per guard`() {
        val guard = AtomicBoolean(false)
        val logs = mutableListOf<String>()
        UpstreamClient.defaultClient(1_000, 1_000, log = { logs.add(it) }, noDelayGuard = guard)
        UpstreamClient.defaultClient(1_000, 1_000, log = { logs.add(it) }, noDelayGuard = guard)
        assertEquals(1, logs.count { it.contains("tcp_nodelay") })
    }
}
