// PORT-OF: the mock upstream from server/test/codex-proxy.test.mjs @ pre-public-port-baseline, 1:1 — scenario
// picked from a SCENARIO:<name> tag in body.instructions; /oauth/token counts refreshes;
// 'refresh' 401s on the old token; every streaming scenario's event sequence is verbatim
// (incl. the nonstream_tool mid-codepoint ✓ split and the prefill silent-then-stream shape).
// ADDED (named change, P2-MOCK slot): count_tokens has NO scenario here — the Kotlin router
// gives it a dedicated cheap handler; the old Node behavior (forwarding it as a real turn)
// is documented in the ledger, not reproduced.
package splice.head

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Bound for the test mock's zstd decode — far above any fixture request. */
private const val MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024

// ADDED (named change, NF-03): sleep past a tiny totalCap BEFORE response headers — the connect/headers window no
// stream-scoped poller ever covered. The turn must be reaped by the whole-turn cap poller while this thread is still
// sleeping.
private const val PRE_HEADERS_STALL_MS = 3_000L

// The two summary sections the "foldsummary" scenario streams (both over the 20-char dedup floor):
// section A is re-titled verbatim by the continuation round, B only ever arrives in round 2.
const val SUMMARY_SECTION_A: String = "**Analyzing CLI exit and async error handling**"
const val SUMMARY_SECTION_B: String = "**Deploying the hardened fleet build**"

// NF-01 quota429 scenario: the HTTP status a real ChatGPT quota rejection carries.
const val RATE_LIMITED_STATUS: Int = 429

/** A broken pipe mid-stream is these scenarios' EXPECTED client-abort exit. DR-7 adds foldstall: the head's idle
 *  watchdog reaps the stalled round, so the server thread wakes from its sleep onto a socket the head already hung up. */
private val ABORT_EXPECTED = setOf("drip", "hold", "holdtool", "foldstall", "idlepre")

class MockChatGptUpstream(
    /** V4-111: the one wall-clock seam in this double. A METHOD REFERENCE to the real sleeper, not
     *  a call to it, so the default behaviour is unchanged while the shape stays an injected port a
     *  test can replace with virtual time — a direct call would make every paced scenario
     *  un-drivable and put the file permanently on the kt-tests-no-wall-clock allowlist. */
    private val pacer: (Long) -> Unit = Thread::sleep,
) {
    val upstreamAuths = CopyOnWriteArrayList<Pair<String, String?>>()
    val upstreamAccountIds = CopyOnWriteArrayList<Pair<String, String?>>()

    /** The `session-id` and `thread-id` headers of each request, in arrival order. */
    val upstreamRouting = CopyOnWriteArrayList<Pair<String?, String?>>()
    val upstreamBodies = CopyOnWriteArrayList<Pair<String, String>>()
    val abortedScenarios = CopyOnWriteArrayList<String>()
    val refreshCalls = AtomicInteger(0)

    private val latches = MockHoldLatches()
    private val refusals = MockRefusals(upstreamBodies)

    // The "hold" scenario blocks after its first delta until the test releases this latch — a
    // deterministic replacement for the timer-based "idle" hold when a test must occupy a slot and
    // then free it on command (review 2026-07-23).
    val holdRelease: CountDownLatch get() = latches.hold

    /** Arm a fresh hold latch — tests that use SCENARIO:hold call this first, then countDown. */
    fun resetHold() {
        latches.hold = CountDownLatch(1)
    }

    // SEPARATE latch from [holdRelease] on purpose: "holdstart" blocks BEFORE any event while "hold" blocks AFTER its
    // first delta. One latch per scenario keeps the two independent.
    val startHoldRelease: CountDownLatch get() = latches.start

    /** Arm a fresh start-hold latch — tests that use SCENARIO:holdstart call this, then countDown. */
    fun resetStartHold() {
        latches.start = CountDownLatch(1)
    }

    fun releaseHold() {
        latches.hold.countDown()
    }

    /** Arm the second hold of SCENARIO:holdtool; [releaseTool] lets that turn finish. */
    fun resetToolHold() {
        latches.tool = CountDownLatch(1)
    }

    fun releaseTool() {
        latches.tool.countDown()
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        server.executor = pool
        server.createContext("/oauth/token") { ex ->
            refreshCalls.incrementAndGet()
            val body = """{"access_token":"tok-new","refresh_token":"refresh-2","id_token":"id-2"}"""
            ex.sendResponseHeaders(HTTP_OK, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        val raw = readBody(ex)
        val body = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val wire = MockSseWire(ex, pacer)
        // FALSE-GREEN GENERATOR, closed (review 2026-08-12). When the mock cannot read the request
        // it used to fall through to the `?: "basic"` default and serve the HAPPY PATH: that
        // is how the zstd change made `authfail` silently receive a success stream and its sibling
        // "pass" for the wrong reason. `?: "basic"` is a legitimate default for a parseable body
        // with no SCENARIO marker; it is never a legitimate answer to "I could not decode this".
        if (body == null) {
            wire.respond(HTTP_BAD_REQUEST, """{"error":{"message":"mock could not parse the request body"}}""")
            return
        }
        // responses-lite turns carry instructions as a developer input item, not the top-level
        // field — scan the whole raw body so scenarios ride either shape.
        val scenario = Regex("SCENARIO:(\\w+)").find(raw)?.groupValues?.get(1) ?: "basic"
        val auth = record(ex, scenario, raw)
        val refusal = refusals.refusalFor(scenario, auth)
        when {
            refusal != null -> wire.respond(refusal.status, refusal.body)
            scenario == "tear" -> refusals.tear(ex)
            scenario == "quota429" -> refusals.quotaRejection(ex)
            else -> stream(wire, scenario, body)
        }
    }

    // CX-03: the real ChatGPT endpoint accepts `content-encoding: zstd` (codex-cli 0.145.0 sends it), and splice now
    // does the same on the codex head. A mock that only reads plaintext would silently fall through to the "basic"
    // scenario on every compressed request — which is exactly what happened when zstd first landed: one test failed
    // and the rest passed for the wrong reason. Decode like the real upstream.
    private fun readBody(ex: HttpExchange): String {
        val rawBytes = ex.requestBody.readBytes()
        return if (ex.requestHeaders.getFirst("Content-Encoding")?.contains("zstd") == true) {
            com.github.luben.zstd.Zstd.decompress(rawBytes, MAX_DECOMPRESSED_BYTES).decodeToString()
        } else {
            rawBytes.decodeToString()
        }
    }

    /** Records what this request carried, in arrival order, and returns its Authorization header. */
    private fun record(ex: HttpExchange, scenario: String, raw: String): String? {
        val auth = ex.requestHeaders.getFirst("Authorization")
        upstreamAuths.add(scenario to auth)
        upstreamAccountIds.add(scenario to ex.requestHeaders.getFirst("ChatGPT-Account-ID"))
        upstreamRouting.add(ex.requestHeaders.getFirst("session-id") to ex.requestHeaders.getFirst("thread-id"))
        upstreamBodies.add(scenario to raw)
        return auth
    }

    private fun stream(wire: MockSseWire, scenario: String, body: JsonObject) {
        val ex = wire.exchange
        if (scenario == "stall") wire.pause(PRE_HEADERS_STALL_MS)
        if (scenario == "ratelimit") {
            // Upstream token-budget headers TurnDriver.persistRateLimit harvests into UsageStore.
            ex.responseHeaders.add("x-ratelimit-limit-tokens", "5000")
            ex.responseHeaders.add("x-ratelimit-remaining-tokens", "1200")
            ex.responseHeaders.add("x-ratelimit-reset-tokens", "6m0s")
        }
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(HTTP_OK, 0)
        try {
            streamScenario(scenario, wire, body)
        } catch (abort: IOException) {
            val expected = scenario in ABORT_EXPECTED
            if (expected) abortedScenarios.add(scenario)
            check(expected) {
                "unexpected mid-stream I/O failure in scenario '$scenario': ${abort.message}"
            }
        } finally {
            Cancellables.discard(runCatching { ex.responseBody.close() }, "test-server teardown")
            Cancellables.discard(runCatching { ex.close() }, "test-server teardown")
        }
    }

    private fun streamScenario(scenario: String, wire: MockSseWire, body: JsonObject) {
        val played = MockFoldScenarios(wire).play(scenario, body) ||
            MockStallScenarios(wire, latches).play(scenario) ||
            MockFailureScenarios(wire).play(scenario)
        if (!played) MockContentScenarios(wire).play(scenario)
    }
}
