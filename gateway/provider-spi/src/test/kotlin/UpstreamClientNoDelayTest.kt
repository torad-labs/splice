// G26, closed by V4-141: TCP_NODELAY on the upstream client hop is now SET by KeepaliveSocketFactory
// (the JDK engine it replaced exposed no socket API, JDK-8338681), and defaultClient() logs what is
// armed once per guard instance. UpstreamTransportOkHttpTest pins the socket options themselves;
// this pins the once-ness. UpstreamClientConnectTimeoutTest ALSO calls defaultClient() directly (a
// real-socket connect-timeout probe, unrelated to logging) — sharing the production guard across
// test classes would make "exactly once" order-dependent on which test class the JUnit engine
// happens to run first, so this test pins its own fresh AtomicBoolean via the noDelayGuard
// parameter rather than relying on the process-wide default.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.spi.UpstreamTransport
import java.util.concurrent.atomic.AtomicBoolean

class UpstreamClientNoDelayTest {

    @Test
    fun `client tcp_nodelay diagnostic logs exactly once per guard`() {
        val guard = AtomicBoolean(false)
        val logs = mutableListOf<String>()
        UpstreamTransport().defaultClient(1_000, 1_000, log = { logs.add(it) }, noDelayGuard = guard)
        UpstreamTransport().defaultClient(1_000, 1_000, log = { logs.add(it) }, noDelayGuard = guard)
        assertEquals(1, logs.count { it.contains("tcp_nodelay") })
    }
}
