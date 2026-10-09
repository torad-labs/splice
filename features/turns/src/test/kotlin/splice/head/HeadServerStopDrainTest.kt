// NEW (V4-74): a stop() while a turn is MID-STREAM must DRAIN, not tear the client's socket.
//
// The measured bug: on a daemon restart the client printed "API Error: Connection lost mid-response"
// and did not retry. The cause was not the restart itself but Ktor's own per-engine JVM shutdown
// hook disposing the application scope concurrently with the daemon's ordered stop, so the SSE write
// failed and the turn ended as a conn-reset AFTER content — plus a drain budget (5s) that could not
// outlive the operator's 7-16s deepseek turns even when it did get to run.
//
// The assertions are on WHAT THE CLIENT RECEIVED, never on splice's internal call counts: the row's
// root-cause lesson is that policy-mirroring tests ratified every regression they were written to
// prevent. So the test holds a turn, calls stop(), releases the turn INSIDE the drain window, and
// requires the client's read to complete with a terminal event instead of throwing.
//
// WHY THE HOLD IS LONGER THAN THE OLD DRAIN: this test is also the mutation proof for the ladder.
// Set DEFAULT_STOP_DRAIN_MS back to 5s and the 6.5s hold outlives it, the drain times out, the engine stops
// mid-turn and the client's read tears — recorded in the row note with its hash. Under the fixed
// 45s ladder the turn finishes comfortably inside the drain.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessWaiter
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

// why 6.5s: it must OUTLIVE the old 5s DEFAULT_STOP_DRAIN_MS so reverting the ladder to 5s makes the
// drain time out and this test fail (the ladder's mutation proof); under the fixed 45s ladder
// the turn still finishes inside the drain.
private const val DRAIN_HOLD_MS = 6_500L

// why 1.5s: a turn held past the drain is cut when the budget is spent, so the budget is the test's wait; 1.5s
// is long enough for the stop to start draining before it gives up, and short enough to cost the run nothing.
private const val SHORT_DRAIN_MS = 1_500L

// why 100ms: the inflight precondition flips once the turn holds a slot; 100ms keeps the 5s
// bound (50 tries) from busy-spinning while still observing a slot promptly.
private const val INFLIGHT_POLL_MS = 100L

private const val EXPECTED_RESTART_SENTENCE = "Splice restarted while this request was running. Retry the request."

private class DrainFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-drain", "acct-drain")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class HeadServerStopDrainTest {

    /** A real HeadServer over the mock upstream, on an OS-assigned port it binds itself (0) and
     *  reports after start. maxRetries = 1 so the turn reaches the hold scenario and STAYS there
     *  rather than backing off through it. */
    private class Rig(
        tmp: Path,
        watchdog: WatchdogBudget = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
        waiter: Waiter = ProcessWaiter(),
        stopDrainMs: Long = DEFAULT_STOP_DRAIN_MS,
    ) {
        val mock = MockChatGptUpstream()
        val cutAt = AtomicLong(0L)
        val gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 })
        val client = HttpClient(CIO) {
            engine { requestTimeout = 90_000 }
            defaultRequest { bearerAuth("test-inference-token") }
        }
        val port: Int get() = head.port
        val head = HeadServer(
            provider = TestResponsesProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = DrainFakeAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = watchdog,
                    loginCommand = "claudex login",
                ),
                reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 900_000, maxRetries = 1),
                gate = gate,
                seams = HeadDeps.HeadSeams(waiter = waiter),
                policy = HeadDeps.HeadPolicy(stopDrainMs = stopDrainMs),
                log = { line ->
                    if (line.contains("draining timed out")) cutAt.compareAndSet(0L, System.nanoTime())
                },
            ),
        )

        suspend fun start() {
            mock.resetHold()
            head.start()
            awaitListening(port)
        }

        suspend fun close() {
            head.stop()
            client.close()
            mock.stop()
        }

        /** The held turn, read to its END: a torn socket throws here, which is the failure this
         *  test exists to catch, so the read itself is part of the assertion. */
        suspend fun heldTurn(stream: Boolean = true): HttpResponse =
            client.post("http://127.0.0.1:$port/v1/messages") {
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":$stream,"max_tokens":64,
                        "system":"You are a test. SCENARIO:hold",
                        "messages":[{"role":"user","content":"go"}]}""",
                )
            }

        suspend fun awaitInflight(): Boolean {
            repeat(50) {
                if (gate.snapshot().inflight >= 1) return true
                delay(INFLIGHT_POLL_MS)
            }
            return gate.snapshot().inflight >= 1
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a turn beyond the drain receives a restart overload before its socket closes`(
        stream: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp, WatchdogBudget(600.seconds, 600.seconds, 900.seconds), stopDrainMs = SHORT_DRAIN_MS)
        rig.start()
        try {
            val turn = async(Dispatchers.IO) {
                val response = rig.heldTurn(stream)
                response.status.value to response.bodyAsText()
            }
            assertTrue(rig.awaitInflight(), "precondition: a real client still holds this request")
            val began = System.nanoTime()
            val stopping = async(Dispatchers.IO) { rig.head.stop() }
            val (status, body) = turn.await()
            val receivedAt = System.nanoTime()
            stopping.await()
            val stoppedAt = System.nanoTime()
            assertTrue(rig.cutAt.get() > began, "the measured cut must follow the drain")
            println(
                "stream=$stream restart_cut_terminal_ns=${receivedAt - rig.cutAt.get()} stop_ns=${stoppedAt - began}",
            )
            assertEquals(if (stream) 200 else 529, status)
            assertTrue(body.contains("overloaded_error"), "a connected client must receive an automatic-retry error")
            assertTrue(body.contains(EXPECTED_RESTART_SENTENCE), "the terminal must name the owner of the cut")
            assertTrue(!body.contains("operator stopped"), "a restart never impersonates a user stop")
            assertTrue(AsyncFileIo.drain())
            val outcome = Files.readAllLines(tmp.resolve("perf.jsonl"))
                .map { Json.parseToJsonElement(it).jsonObject }
                .single()["outcome"]?.jsonPrimitive?.content
            assertEquals(OutcomeTag.RESTARTED.wire, outcome)
            assertTrue(OutcomeTags.isFailed(outcome.orEmpty()))
            assertTrue(!OutcomeTags.isStopped(outcome.orEmpty()))
            rig.mock.releaseHold()
            rig.head.start()
            awaitListening(rig.port)
            val retry = rig.heldTurn(stream)
            val retryBody = retry.bodyAsText()
            assertEquals(
                200,
                retry.status.value,
                "the same request is accepted after restart without an operator stop mark",
            )
            assertTrue(!retryBody.contains("overloaded_error"), "the restarted driver must accept new turn jobs")
            assertTrue(retryBody.contains(if (stream) "message_stop" else "stop_reason"))
        } finally {
            rig.mock.releaseHold()
            rig.close()
        }
    }

    @Test
    fun `an empty shutdown never waits for a cancellation seal`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig(tmp, waiter = Waiter { error("an empty head must not wait") })
        rig.start()
        try {
            val began = System.nanoTime()
            rig.head.stop()
            println("empty_stop_ns=${System.nanoTime() - began}")
            assertEquals(0, rig.gate.snapshot().inflight)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a stop during a held turn drains it to its terminal instead of tearing the socket`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp)
        rig.start()
        try {
            val turn = async(Dispatchers.IO) { rig.heldTurn().bodyAsText() }
            assertTrue(rig.awaitInflight(), "precondition: the turn must be holding a slot")

            // THE STOP, issued while the turn is mid-stream, on its own dispatcher so the drain and
            // the turn overlap the way they do under SIGTERM.
            val stopping = async(Dispatchers.IO) { rig.head.stop() }

            // The turn finishes INSIDE the drain window, and the hold outlives the OLD 5s drain on
            // purpose: that is what makes this test the ladder's mutation proof.
            delay(DRAIN_HOLD_MS)
            rig.mock.releaseHold()

            val body = turn.await() // a torn socket fails HERE, by throwing
            stopping.await()

            assertTrue(
                body.contains("message_stop") || body.contains("event: error"),
                "the drained turn must reach the client as a real terminal, not a cut stream; got: " +
                    body.take(300),
            )
        } finally {
            rig.close()
        }
    }
}
