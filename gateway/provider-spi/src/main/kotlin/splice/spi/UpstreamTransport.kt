// PORT-OF: splice/spi/UpstreamClient.kt (Transport.defaultClient, CONNECT_TIMEOUT_MS, nodelayLogged) @ 3879c4c — invariants unchanged: still the ONE HttpClient construction in the tree, and every timeout, engine and protocol setting below is byte-for-byte the original.
//
// The ONE HttpClient construction in the tree (HD-25). Was UpstreamClient.Transport.defaultClient;
// only the receiver moved — every timeout, engine and protocol setting below is byte-for-byte the
// original, and UpstreamClient's `client` constructor default still calls it.
//
// Transport lessons from Grok Build / Codex CLI that this file is the home of:
//   - shorter keepAlive than upstream idle so we don't reuse LB-killed sockets
//   - pipelineMaxSize=1 so a cancelled SSE cannot poison siblings
// The failure CLASSIFICATION half of the old Transport lives in TransportFailures.kt; the G5
// re-issue interlock that used to sit beside it went to RetryPolicy.kt, where its three loop-budget
// facts are.
package splice.spi

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import splice.core.util.LogSink
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

public class UpstreamTransport {
    public fun defaultClient(
        firstByteTimeoutMs: Long,
        totalTimeoutMs: Long,
        log: LogSink = LogSink {},
        noDelayGuard: AtomicBoolean = nodelayLogged,
    ): HttpClient {
        if (noDelayGuard.compareAndSet(false, true)) {
            log(
                "[upstream] tcp_nodelay(client)=unverifiable: java.net.http.HttpClient exposes " +
                    "no public API to read or set TCP_NODELAY per connection (JDK-8338681, open)\n",
            )
        }
        return HttpClient(Java) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = totalTimeoutMs
                socketTimeoutMillis = firstByteTimeoutMs
            }
            engine {
                // JDK HttpClient (async NIO), replacing ktor CIO. CIO's socket writer busy-spun
                // on a non-writable upstream socket (macOS/kqueue) and melted CPU under
                // concurrent large-body streams — 9 writer coroutines pegged cores and starved
                // the coroutine dispatcher to 103 workers (busy-loop jstack, 2026-07-18). The
                // JDK engine parks on write backpressure and drives the 1000-stream target on a
                // shared selector, not a thread-per-write. HTTP/1.1 only — parity with the CIO
                // lineage (undici allowH2:false): one stream per connection, cancel-safe; the
                // app-level retry loop already owns connect retries.
                protocolVersion = java.net.http.HttpClient.Version.HTTP_1_1
            }
        }
    }

    /** Exponential backoff, ±10% jitter (codex shape — synchronized retry herds re-collide without
     *  it), capped at MAX_BACKOFF_MS; a server Retry-After rides in as a FLOOR via minDelayMs (G3).
     *  Sleeps through [waiter] so a test can replace the WAIT without re-authoring the CURVE. */
    public fun defaultBackoff(waiter: Waiter): RetryBackoff = RetryBackoff { attempt, minDelayMs ->
        val base = minOf(BACKOFF_BASE_MS shl attempt, MAX_BACKOFF_MS)
        val jittered = (base * Random.nextDouble(JITTER_LO, JITTER_HI)).toLong()
        waiter.wait(maxOf(jittered, minDelayMs))
    }

    /** DNS-class transport failures (G14) get their own 1s/2s/4s schedule — a real resolver
     *  blip (kimi 07:00 burst: 37 UnresolvedAddressException turns) runs longer than the
     *  generic 200/400/800ms curve undershoots. No minDelayMs — transport errors never carry
     *  a Retry-After header (no response was received). */
    public fun defaultDnsBackoff(waiter: Waiter): DnsBackoff = DnsBackoff { attempt ->
        val base = minOf(DNS_BACKOFF_BASE_MS shl attempt, DNS_MAX_BACKOFF_MS)
        val jittered = (base * Random.nextDouble(JITTER_LO, JITTER_HI)).toLong()
        waiter.wait(jittered)
    }
}

// G11: a blackholed/dead address must fail fast into the existing transport-retry loop
// (isRetryableTransport) instead of stalling to the OS SYN timeout x maxRetries. Decoupled
// from firstByteTimeoutMs (5min default), which governs headers-wait/body phase via
// socketTimeoutMillis, not TCP connect.
private const val CONNECT_TIMEOUT_MS = 10_000L

private const val BACKOFF_BASE_MS = 200L
private const val MAX_BACKOFF_MS = 10_000L
private const val JITTER_LO = 0.9
private const val JITTER_HI = 1.1
private const val DNS_BACKOFF_BASE_MS = 1_000L
private const val DNS_MAX_BACKOFF_MS = 4_000L

// G26: java.net.http.HttpClient/Builder expose no public API to read or set TCP_NODELAY per
// connection (confirmed via javap on ktor-client-java-jvm; JDK-8338681 is an open
// enhancement request for exactly this, still unresolved). Reflecting into
// jdk.internal.net.http internals is fragile/module-encapsulated and disproportionate for a
// LOW one-time diagnostic — the honest move is to log that verification is impossible via
// public API, once per JVM (a JVM-wide guard so N heads sharing one daemon log it once, not
// N times each time a head is assembled). Injectable (same pattern as backoff/dnsBackoff/
// clock above) so a test can pin its own guard instead of sharing process-wide state with
// every other direct defaultClient() caller (UpstreamClientConnectTimeoutTest calls it too,
// for its own unrelated real-socket connect-timeout probe).
// FILE SCOPE ON PURPOSE: the guard is JVM-wide BY CONTRACT — as an UpstreamTransport field, every
// `UpstreamTransport()` would carry a fresh guard and the once-per-JVM log would fire per client.
private val nodelayLogged = AtomicBoolean(false)
