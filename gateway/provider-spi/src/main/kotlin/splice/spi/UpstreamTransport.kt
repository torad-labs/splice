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
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
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

    /** Exponential backoff, jittered ±[jitterPct]% (codex shape — synchronized retry herds re-collide
     *  without it), capped at [capMs]; a server Retry-After rides in as a FLOOR via minDelayMs (G3).
     *  Sleeps through [waiter] so a test can replace the WAIT without re-authoring the CURVE. The
     *  base/cap/jitter are the generic bounded curve an unpredicted failure falls onto (V4-110). */
    public fun defaultBackoff(
        waiter: Waiter,
        baseMs: Long = BACKOFF_BASE_MS,
        capMs: Long = MAX_BACKOFF_MS,
        jitterPct: Int = JITTER_PCT,
    ): RetryBackoff = RetryBackoff { attempt, minDelayMs ->
        val base = cappedExponentialBase(baseMs, capMs, attempt)
        val jittered = (base * jitterMultiplier(jitterPct)).toLong()
        waiter.wait(maxOf(jittered, minDelayMs))
    }

    /**
     * V4-125 fallback: an OUT-OF-BAND reachability probe for [Watchdog], used when a round has sat
     * past its idle tier and the socket itself cannot say whether anyone is still there.
     *
     * It opens a NEW connection to the provider's own host and port and closes it. A **refused**
     * connection is returned as `false` — a definite "not here", which is the only answer that ends a
     * round. Everything else (a name that will not resolve, a timeout in the probe itself) is left to
     * THROW and is read upstream as inconclusive, because the alternative is a probe that turns a
     * local network hiccup into a dead turn. [Watchdog.probeAgrees] is where that asymmetry lives.
     *
     * HONEST ABOUT ITS LIMIT, because it is easy to over-read: this proves the PATH, not THIS CALL.
     * A refusal is real evidence that no amount of waiting will produce a token, but a success says
     * nothing about the specific connection the round is parked on — a half-open one still reads
     * healthy here. It is a weaker instrument than TCP keepalive, which is why keepalive is the
     * preferred route and this is the fallback (V4-141 carries the engine work).
     */
    public fun reachabilityProbe(url: String, timeoutMs: Long = PROBE_TIMEOUT_MS): ProviderProbe = ProviderProbe {
        val target = probeTarget(url) ?: return@ProviderProbe true
        try {
            Socket().use { socket -> socket.connect(target, timeoutMs.toInt()) }
            true
        } catch (_: ConnectException) {
            // The ONE answer that is evidence of death: the host answered, and said no.
            false
        } catch (_: IOException) {
            // Everything else — a name that will not resolve, a connect that times out, a route that
            // is briefly gone — is the probe failing to get an answer rather than a refusal. It reads
            // as reachable, so a local network hiccup cannot kill a healthy turn.
            true
        }
    }

    /** The address a probe dials, or null when the URL names nothing dialable — which is a failure to
     *  ask rather than an answer, so the caller reads it as reachable. */
    private fun probeTarget(url: String): InetSocketAddress? {
        val parsed = try {
            URI(url)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val host = parsed.host ?: return null
        val port = if (parsed.port > 0) parsed.port else DEFAULT_HTTPS_PORT
        return InetSocketAddress(host, port)
    }

    /** DNS-class transport failures (G14) get their own 1s/2s/4s schedule — a real resolver
     *  blip (kimi 07:00 burst: 37 UnresolvedAddressException turns) runs longer than the
     *  generic 200/400/800ms curve undershoots. No minDelayMs — transport errors never carry
     *  a Retry-After header (no response was received). Shares the jitter knob with the generic
     *  curve so the budget check and the sleep cannot drift apart. */
    public fun defaultDnsBackoff(waiter: Waiter, jitterPct: Int = JITTER_PCT): DnsBackoff = DnsBackoff { attempt ->
        val base = cappedExponentialBase(DNS_BACKOFF_BASE_MS, DNS_MAX_BACKOFF_MS, attempt)
        val jittered = (base * jitterMultiplier(jitterPct)).toLong()
        waiter.wait(jittered)
    }

    /** A multiplier in [1 - pct/100, 1 + pct/100) — the shared jitter range for both curves. Zero
     *  jitter is the exact multiplier, never a zero-width Random range (which would throw). */
    private fun jitterMultiplier(pct: Int): Double =
        if (pct == 0) 1.0 else Random.nextDouble((100 - pct) / 100.0, (100 + pct) / 100.0)

    private fun cappedExponentialBase(baseMs: Long, maxMs: Long, attempt: Int): Long {
        var current = baseMs
        repeat(attempt.coerceAtLeast(0)) {
            current = if (current > maxMs / 2) maxMs else current * 2
        }
        return minOf(current, maxMs)
    }
}

// G11: a blackholed/dead address must fail fast into the existing transport-retry loop
// (isRetryableTransport) instead of stalling to the OS SYN timeout x maxRetries. Decoupled
// from firstByteTimeoutMs (5min default), which governs headers-wait/body phase via
// socketTimeoutMillis, not TCP connect.
private const val CONNECT_TIMEOUT_MS = 10_000L

// V4-125: how long the out-of-band probe waits before giving up. Short on purpose — it runs while a
// round is parked past its idle tier, so a long probe would delay the very decision it exists to
// inform; and a timeout is treated as inconclusive rather than as death, so being impatient here
// costs nothing but a poll.
private const val PROBE_TIMEOUT_MS = 3_000L

// The port a provider URL without an explicit one means. Tencent/Anthropic-style upstreams are all
// https, and the probe is a TCP connect, so the scheme only decides this number.
private const val DEFAULT_HTTPS_PORT = 443

// V4-100: these five numbers are the single source, which is the point rather than a widening.
// UpstreamClient next door re-typed them as its own private consts, because it budgets a curve
// before sleeping it and could not read these; a re-typed number is one that can drift, and the
// drift would be silent in exactly the wrong direction — UpstreamClient would approve a wait the
// curve then exceeds. It reads THESE now, so the budget check and the sleep read one number.
//
// V4-122 item 9: INTERNAL, which is that same single-sourcing seen from the other side. The one
// reader is UpstreamClient in THIS module, so `public` declared a surface no other module consumes —
// exactly what checks/public-surface.py reds as unjustified, and the first remedy it names is this
// one. Narrowing was checked before it was made: the only match for any of the five outside
// :provider-spi is HostedServer.kt's own private BACKOFF_BASE_MS = 5_000L, a different declaration
// with a different value, so no consumer is cut off. Single-sourcing is untouched either way.
internal const val BACKOFF_BASE_MS: Long = 200L
internal const val MAX_BACKOFF_MS: Long = 10_000L
internal const val JITTER_PCT: Int = 10
internal const val DNS_BACKOFF_BASE_MS: Long = 1_000L
internal const val DNS_MAX_BACKOFF_MS: Long = 4_000L

// G26: java.net.http.HttpClient/Builder expose no public API to read or set TCP_NODELAY per
// connection (confirmed via javap on ktor-client-java-jvm; JDK-8338681 is an open
// enhancement request for exactly this, still unresolved). Reflecting into
// jdk.internal.net.http internals is fragile/module-encapsulated and disproportionate for a
// LOW one-time diagnostic — the honest move is to log that verification is impossible via
// public API, once per JVM (a JVM-wide guard so N heads sharing one daemon log it once, not
// N times each time a head is assembled). Injectable like the backoff seams above (and
// UpstreamClient's clock seam), so a test can pin its own guard instead of sharing process-wide state with
// every other direct defaultClient() caller (UpstreamClientConnectTimeoutTest calls it too,
// for its own unrelated real-socket connect-timeout probe).
// FILE SCOPE ON PURPOSE: the guard is JVM-wide BY CONTRACT — as an UpstreamTransport field, every
// `UpstreamTransport()` would carry a fresh guard and the once-per-JVM log would fire per client.
private val nodelayLogged = AtomicBoolean(false)
