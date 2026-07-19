// G11: TCP connect used to ride the 5-minute firstByteTimeoutMs (connectTimeoutMillis was wired
// to the same knob as the headers/body-wait phase), so a blackholed/dead address stalled to the
// OS SYN timeout x maxRetries instead of failing fast into the transport-retry loop. This pins
// defaultClient()'s connectTimeoutMillis at a fixed 10s, decoupled from firstByteTimeoutMs.
// Real sockets required — MockEngine never opens one, so it can't exercise connectTimeoutMillis.
//
// The pinned-spec reproduction (fill a ServerSocket(0, 1)'s single accept-queue slot and never
// accept()) does NOT exercise connectTimeoutMillis on Linux and was replaced after it hung for
// 500s+ in a real run here: with net.ipv4.tcp_abort_on_overflow=0 (this box's default, and the
// default on virtually every Linux box), the kernel completes the 3-way handshake and only drops
// the connection server-side once the accept queue is checked at ACK time — the CLIENT sees an
// immediate ESTABLISHED socket and hangs on the READ phase (socketTimeoutMillis) instead, never
// touching connectTimeoutMillis at all. Confirmed both by a raw java.net.http.HttpClient repro
// (hung 150s+ past its own 5s connectTimeout against the identical setup) and independently:
// https://veithen.io/2014/01/01/how-tcp-backlog-works-in-linux.html ("half-open connection").
// TEST-NET-1 (192.0.2.0/24, RFC 5737) is used instead: guaranteed unassigned/unrouted, so SYNs
// go out and are never answered — a genuine, deterministic connect-phase black hole, and the
// standard technique other HTTP client test suites use for exactly this purpose. Verified locally
// (raw java.net.http.HttpClient with connectTimeout(4s) against it threw right at ~4016ms).
import io.ktor.client.request.preparePost
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.discard
import splice.spi.UpstreamClient

private const val BLACKHOLE_URL = "http://192.0.2.1:9999/v1"

class UpstreamClientConnectTimeoutTest {

    @Test
    fun `connect timeout fires around 10s, decoupled from a much larger firstByteTimeoutMs`() {
        val client = UpstreamClient.defaultClient(firstByteTimeoutMs = 300_000L, totalTimeoutMs = 900_000L)
        val start = System.nanoTime()
        val thrown = runCatching {
            runBlocking {
                client.preparePost(BLACKHOLE_URL) {}.execute { it.status }
            }
        }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(thrown != null, "expected a connect failure against a blackholed address")
        assertTrue(
            elapsedMs < 20_000,
            "connect must fail fast (~10s), not ride the 300_000ms firstByteTimeoutMs; took ${elapsedMs}ms",
        )
        assertTrue(
            UpstreamClient.isRetryableTransport(thrown!!),
            "the thrown exception must compose with the existing transport-retry loop: ${thrown::class}",
        )
    }

    @Test
    fun `connect timeout does not inherit a shorter firstByteTimeoutMs`() {
        // firstByteTimeoutMs is deliberately SHORTER than the fixed 10s connect timeout — if
        // connect still derived from firstByteTimeoutMs, this would fail around 3s instead of 10s.
        val client = UpstreamClient.defaultClient(firstByteTimeoutMs = 3_000L, totalTimeoutMs = 900_000L)
        val start = System.nanoTime()
        runCatching {
            runBlocking {
                client.preparePost(BLACKHOLE_URL) {}.execute { it.status }
            }
        }.discard("only the elapsed time is asserted; the failure itself is pinned by the other test")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            elapsedMs > 5_000,
            "connect timeout must be decoupled from firstByteTimeoutMs=3000ms; took only ${elapsedMs}ms",
        )
    }
}
