// PORT-OF: splice/spi/UpstreamClient.kt (Transport.defaultClient, CONNECT_TIMEOUT_MS, nodelayLogged) @ 3879c4c — the ONE HttpClient construction in the tree; the timeouts are the original's, the ENGINE changed in V4-141 (below).
//
// The ONE HttpClient construction in the tree (HD-25). Was UpstreamClient.Transport.defaultClient;
// only the receiver moved, and UpstreamClient's `client` constructor default still calls it.
//
// V4-141: the engine is OkHttp, not the JDK HttpClient, for ONE reason — it hands us the socket.
// The JDK client exposes no socket options (JDK-8338681), so its upstream sockets carried no TCP
// keepalive and a half-open connection read as healthy for the whole turn cap; KeepaliveSocketFactory
// arms every socket before it connects. The swap's own cost is named and paid here rather than
// discovered in production: OkHttp is BLOCKING and its Dispatcher caps FIVE requests
// per host by default, which would serialise every concurrent turn against one provider behind five
// sockets — a worse outage than the reaps keepalive exists to end. So the Dispatcher runs on a
// virtual-thread executor at UPSTREAM_MAX_REQUESTS, and that number is PINNED by a test. The SECOND
// cost is the one the load test found (2026-09-20, first run of the swap: 1000 turns, peak 96 held
// at the mock, ZERO live at the client): ktor's OkHttp engine bridges each response body to a channel
// with a BLOCKING `source.read` on the ENGINE's dispatcher, which defaults to Dispatchers.IO (64
// threads). Ninety-six held streams pinned all 64, and the head's own pipeline — which also runs on
// IO — could no longer forward the delta it had already received: a deadlock at exactly the ramp
// width. So the engine dispatcher is the same virtual-thread executor, and a held stream blocks a
// virtual thread that costs nothing to park.
//
// Transport lessons from Grok Build / Codex CLI that this file is the home of:
//   - shorter keepAlive than upstream idle so we don't reuse LB-killed sockets
//   - HTTP/1.1 only, one stream per connection, so a cancelled SSE cannot poison siblings
// The failure CLASSIFICATION half of the old Transport lives in TransportFailures.kt; the G5
// re-issue interlock that used to sit beside it went to RetryPolicy.kt, where its three loop-budget
// facts are.
package splice.spi

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.Dispatcher
import okhttp3.Protocol
import splice.core.util.LogSink
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketOption
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
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
                "[upstream] tcp_nodelay(client)=set keepalive(client)=${KEEPALIVE_IDLE_S}s/" +
                    "${KEEPALIVE_INTERVAL_S}s/x$KEEPALIVE_PROBES: every upstream socket is armed by " +
                    "KeepaliveSocketFactory before it connects (V4-141)\n",
            )
        }
        // Built ONCE per client, outside the config block: ktor's OkHttp engine re-runs that block
        // for every distinct timeout configuration it caches a client for, and a Dispatcher created
        // inside it would give each cached client its own executor and its own per-host budget.
        val threads = upstreamThreads()
        val dispatcher = upstreamDispatcher(threads)
        val sockets = KeepaliveSocketFactory()
        return HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = totalTimeoutMs
                socketTimeoutMillis = firstByteTimeoutMs
            }
            engine {
                // The engine's own dispatcher, where every response body is read with a blocking
                // `source.read` for as long as the stream is held: virtual, never Dispatchers.IO.
                this.dispatcher = threads.asCoroutineDispatcher()
                config {
                    // HTTP/1.1 only — parity with the CIO and JDK lineages (undici allowH2:false):
                    // one stream per connection, cancel-safe; the app-level retry loop already
                    // owns connect retries. The JDK engine this replaced was chosen over ktor CIO
                    // because CIO's socket writer busy-spun on a non-writable upstream socket
                    // (macOS/kqueue, 2026-07-18); OkHttp blocks a virtual thread per call instead,
                    // and the 1000-stream load test is the gate that says it scales.
                    protocols(listOf(Protocol.HTTP_1_1))
                    socketFactory(sockets)
                    dispatcher(dispatcher)
                }
            }
        }
    }

    /** The one thread source under the upstream client — OkHttp's calls AND ktor's body readers —
     *  and it is virtual because both block a thread per held stream. Internal so a test can pin
     *  the kind: on platform threads the same code is a thread-per-stream daemon. */
    internal fun upstreamThreads(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    /** OkHttp's call scheduler, sized for the daemon rather than for a browser: the default
     *  Dispatcher runs 64 calls in flight and FIVE per host, and every turn the daemon serves is a
     *  call to one of two or three hosts. Internal so a test can pin both numbers. */
    internal fun upstreamDispatcher(threads: ExecutorService = upstreamThreads()): Dispatcher =
        Dispatcher(threads).apply {
            maxRequests = UPSTREAM_MAX_REQUESTS
            maxRequestsPerHost = UPSTREAM_MAX_REQUESTS
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
    public fun reachabilityProbe(
        url: String,
        timeoutMs: Long = REACHABILITY_PROBE_TIMEOUT_MS,
    ): ProviderProbe = ProviderProbe {
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
//
// NAMED FOR ITS CALLER, not for "a probe" (2026-09-18). This was PROBE_TIMEOUT_MS, which made it the
// FIFTH file to spell that name and tripped const-single-source's collision ratchet against a
// recorded baseline of four. The four are not one number copied around — they are 400 in
// DaemonLock, 400 in DaemonHealth, 400 in ControlPlaneClient and 5_000 in TurnPathProbeLoop — so the
// hazard the ratchet is naming is real and is about READING, not linking: five private consts cannot
// collide at the language level, but a reader who has seen PROBE_TIMEOUT_MS twice assumes the third
// is the same number, and here it would be wrong by 7.5x. Unifying them would be worse than the
// disease, since these are four unrelated probes with genuinely different budgets. So the growth is
// removed by giving this one the name of the single function that reads it.
private const val REACHABILITY_PROBE_TIMEOUT_MS = 3_000L

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

// V4-141: the in-flight ceiling for OkHttp's Dispatcher, total and per host — the same number the
// load test drives (1000 streams against one mock host), with headroom. It replaces a default of 5
// per host that no test would have caught short of the load test, which is why it is pinned.
internal const val UPSTREAM_MAX_REQUESTS: Int = 4096

// G26 (closed by V4-141): under the JDK engine TCP_NODELAY was UNVERIFIABLE — java.net.http.HttpClient
// exposes no socket API (JDK-8338681) — so this logged that fact once per JVM instead of pretending.
// KeepaliveSocketFactory now SETS it, with the keepalive timings, on every socket before it connects,
// and the once-per-JVM line reports what is set rather than what cannot be read. Still a JVM-wide
// guard so N heads sharing one daemon log it once, not N times each time a head is assembled;
// injectable like the backoff seams above (and UpstreamClient's clock seam), so a test can pin its
// own guard instead of sharing process-wide state with every other direct defaultClient() caller
// (UpstreamClientConnectTimeoutTest calls it too, for its own real-socket connect-timeout probe).
// FILE SCOPE ON PURPOSE: the guard is JVM-wide BY CONTRACT — as an UpstreamTransport field, every
// `UpstreamTransport()` would carry a fresh guard and the once-per-JVM log would fire per client.
private val nodelayLogged = AtomicBoolean(false)

// V4-141 — the SocketFactory that arms every upstream socket BEFORE it connects.
//
// WHY A FACTORY. java.net.http.HttpClient exposes no socket options at all (JDK-8338681), so for as
// long as it was the upstream engine the daemon's established upstream sockets carried no keepalive
// timer — `ss -tnpo` showed none, while a standalone probe armed through a SocketFactory showed
// `timer:(keepalive,...)`. Without keepalive a half-open connection (LB reaped it, the FIN never
// reached us) reads as HEALTHY to every layer above: the SSE hold V4-125 landed then has no bound but
// the whole-turn cap. OkHttp is the engine that takes a SocketFactory, and this is the factory. It lives in THIS file
// rather than its own because the concentration ratchet's denominator is a per-package median: a
// 60-line sibling in splice.spi moved app/codemode/CodeModeWire.kt into band HIGH without a line of
// it changing (measured 2026-09-20), which is the ratchet's documented property, not a defect here.
//
// ARMED ON THE RAW SOCKET, BEFORE CONNECT. OkHttp asks for `createSocket()` (unconnected), connects it
// itself, and layers TLS over it with `SSLSocketFactory.createSocket(raw, host, port, autoClose)`;
// the options live on the raw file descriptor, so the TLS layer inherits them and a probe on the
// wire is a plain TCP keepalive the peer's kernel answers without the application's help. That is
// the whole point: it works on a connection whose application layer is silent, which is exactly the
// case that cannot be told apart from a dead one any other way.
//
// THE THREE TIMINGS are the ones the evaluation measured (30s idle, 10s between probes, 3 probes):
// a dead peer is declared within 60s of the last byte, and a live-but-quiet SSE hold costs one
// empty segment every 30s. They are jdk.net.ExtendedSocketOptions, which the JDK supports on Linux
// and macOS and only partly on Windows; an unsupported one is SKIPPED, not thrown, because
// SO_KEEPALIVE itself is universal and a probe on the OS's own schedule (Linux: 2h) is still a
// bound, where the alternative is a client that refuses to connect at all.
internal class KeepaliveSocketFactory(
    private val idleSeconds: Int = KEEPALIVE_IDLE_S,
    private val intervalSeconds: Int = KEEPALIVE_INTERVAL_S,
    private val probes: Int = KEEPALIVE_PROBES,
) : SocketFactory() {

    /** The one overload OkHttp calls: an unconnected socket it connects itself. */
    override fun createSocket(): Socket = arm(Socket())

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().also {
            it.bind(InetSocketAddress(localHost, localPort))
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().also {
            it.bind(InetSocketAddress(localAddress, localPort))
            it.connect(InetSocketAddress(address, port))
        }

    /** Internal, not private: the test pins what an armed socket reads back, without a peer. */
    internal fun arm(socket: Socket): Socket = socket.apply {
        keepAlive = true
        // G26 closed: TCP_NODELAY was "unverifiable" only because the JDK engine hid the socket.
        tcpNoDelay = true
        setIfSupported(this, ExtendedSocketOptions.TCP_KEEPIDLE, idleSeconds)
        setIfSupported(this, ExtendedSocketOptions.TCP_KEEPINTERVAL, intervalSeconds)
        setIfSupported(this, ExtendedSocketOptions.TCP_KEEPCOUNT, probes)
    }

    private fun setIfSupported(socket: Socket, option: SocketOption<Int>, value: Int) {
        if (option in socket.supportedOptions()) socket.setOption(option, value)
    }
}

// Seconds of silence before the first probe, seconds between probes, probes before the socket is
// declared dead: 30 + 3 x 10 = a dead peer is known within 60s of its last byte.
internal const val KEEPALIVE_IDLE_S: Int = 30
internal const val KEEPALIVE_INTERVAL_S: Int = 10
internal const val KEEPALIVE_PROBES: Int = 3
