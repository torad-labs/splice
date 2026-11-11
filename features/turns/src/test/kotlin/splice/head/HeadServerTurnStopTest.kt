// NEW: V4-319 — the operator stops a live turn, on a real head over a held upstream turn.
//
// The assertions are on WHAT THE CLIENT RECEIVES, because the contract is the client's: the stopped
// stream ends with an error frame that says the operator stopped the turn and never says "retry";
// the one stream=false re-send Claude Code makes of an errored stream (console's probe on 2.1.283:
// same session, same messages, 4.4-19.6 ms later) is answered HERE with a 400 and never reaches the
// upstream; and the mark that refuses it is spent by that one refusal. The upstream's request count
// is the proof of "never sent", read from the mock rather than from splice's own counters.
//
// WHERE THE STOP LANDS decides the shape of the answer, so each shape is pinned from its own side. A turn whose
// stream the client already holds (the response head arrived, which the head sends only once it has chosen the
// stream) ends in band, with the error frame. A turn stopped before its first frame has no status yet: the head
// answers the stop's own 400 with x-should-retry false (ClientWire, family P), and never lets the drive's
// cancellation reach Ktor first, which answered a bare 500 whose body was the exception's message. The stream
// tests therefore wait for the response head, the event they mean, and not for the gate's STREAMING phase, which
// is the upstream's headers and lands before the first frame has reached the client.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.head.GatePhase
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.turn.LiveTurns
import splice.head.turn.LiveTurnsByHead
import splice.head.turn.LiveTurnsRoutes
import splice.head.turn.LiveTurnsSource
import splice.head.wire.WireTap
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Ticker
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val WAIT_MS = 10_000L
private const val POLL_MS = 20L
private const val SESSION = "sess-stop-319"

// Failure-handling slice 2: the head's line for a turn stopped from the console, running or re-sent
// (final-design.md section 10). It says what happened and invites nothing.
private const val STOPPED = "[splice] This turn was stopped from the splice console."

private class StopFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-stop", "acct-stop")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** What a client reads of one answer: its status, whether it was told not to send it again, and its whole body. */
private data class Answer(val status: Int, val shouldRetry: String?, val body: String)

class HeadServerTurnStopTest {

    /** A real HeadServer over the mock upstream with its live turns wired to [routes] the way the
     *  control plane wires them. maxRetries = 1 so the held turn stays held rather than backing off. */
    private class Rig(
        private val tmp: Path,
        val tap: WireTap? = null,
        seams: HeadDeps.HeadSeams = HeadDeps.HeadSeams(),
    ) {
        val mock = MockChatGptUpstream()
        val gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 })
        val turns = LiveTurns()
        val journal = CopyOnWriteArrayList<String>()
        val routes = LiveTurnsRoutes(
            heads = TurnsHeadLookup { name ->
                if (name == "codex") listOf(TurnsHead("codex", NoCompaction)) else emptyList()
            },
            registry = LiveTurnsSource { LiveTurnsByHead().apply { put("codex", turns) } },
        )
        private val client = HttpClient(CIO) {
            defaultRequest { bearerAuth("test-inference-token") }
            expectSuccess = false
        }
        val head = HeadServer(
            provider = TestResponsesProvider(
                tuning = ProviderTuning(
                    name = ProviderName(key = "codex", label = "claudex"),
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = StopFakeAuth(),
                    locations = ProviderLocations(baseUrl = mock.baseUrl),
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                    loginCommand = "claudex login",
                ),
                reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 60_000),
                gate = gate,
                log = { journal.add(it) },
                seams = seams,
            ).let { base ->
                base.copy(
                    traffic = base.traffic.copy(liveTurns = turns),
                    stores = headStores(
                        tmp,
                        wireTap = tap,
                        trace = tap?.let {
                            syntheticTraceStore(
                                ActivityDays(tmp.resolve("trace"), "codex", 7, ownerOnly = true),
                                "codex",
                                1 shl 20,
                            )
                        },
                    ),
                )
            },
        )

        suspend fun start() {
            mock.resetHold()
            head.start()
            awaitListening(head.port)
        }

        suspend fun close() {
            mock.releaseHold()
            mock.startHoldRelease.countDown()
            head.stop()
            client.close()
            mock.stop()
        }

        private fun request(text: String, stream: Boolean, scenario: String) =
            """{"model":"claude-codex--gpt-5.6-sol","stream":$stream,"max_tokens":64,
                "system":"You are a test. SCENARIO:$scenario",
                "messages":[{"role":"user","content":"$text"}]}"""

        /** One turn of [SESSION] asking [text], held upstream by the hold scenario until released. */
        suspend fun turn(text: String, stream: Boolean, session: String = SESSION): HttpResponse =
            client.post("http://127.0.0.1:${head.port}/v1/messages") {
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", session)
                setBody(request(text, stream, "hold"))
            }

        /** One streamed turn of [SESSION] read the way a client reads it: [headReceived] completes with the status
         *  the moment the response head arrives, long before the body ends. The head sends one only once it has
         *  chosen its answer, so a 200 here is a stream the client holds. [scenario] is the upstream's: "hold"
         *  streams a first delta and waits, "holdstart" waits before any event. */
        suspend fun streamed(text: String, headReceived: CompletableDeferred<Int>, scenario: String = "hold"): Answer =
            client.preparePost("http://127.0.0.1:${head.port}/v1/messages") {
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", SESSION)
                setBody(request(text, stream = true, scenario))
            }.execute { response ->
                headReceived.complete(response.status.value)
                Answer(response.status.value, response.headers["x-should-retry"], response.bodyAsText())
            }

        suspend fun until(what: String, check: () -> Boolean) {
            withTimeout(WAIT_MS) {
                while (!check()) delay(POLL_MS)
            }
            assertTrue(check(), what)
        }

        fun perfRows(): String = tmp.resolve("perf.jsonl").takeIf(Files::exists)?.let(Files::readString).orEmpty()
    }

    private object NoCompaction : HeadCompactSource {
        override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
    }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `a stopped stream ends saying the operator stopped it, and its re-send is refused here once`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp)
        rig.start()
        try {
            val headReceived = CompletableDeferred<Int>()
            val held = async(Dispatchers.IO) { rig.streamed("go", headReceived) }
            assertEquals(200, withTimeout(WAIT_MS) { headReceived.await() }, "the client holds an open stream")
            rig.until("the turn is streaming upstream") {
                rig.gate.snapshot().live.any { it.phase == GatePhase.STREAMING }
            }

            val listed = json(rig.routes.live("codex").body)
            val turn = listed["turns"]!!.jsonArray.single().jsonObject
            assertEquals(SESSION, turn["session"]!!.jsonPrimitive.content, "$listed")
            assertEquals("gpt-5.6-sol", turn["model"]!!.jsonPrimitive.content, "$listed")
            val id = turn["id"]!!.jsonPrimitive.content
            assertEquals(id, rig.gate.snapshot().live.single().turnId, "the gate names the exact live stop target")

            val stop = rig.routes.stop("codex", id)
            assertEquals(200, stop.status.value, stop.body)
            assertEquals("{}", stop.body)

            // Bounded: a stop that cancels nothing leaves the turn held, and that must fail, not hang.
            val body = withTimeout(WAIT_MS) { held.await() }.body
            assertTrue(body.contains("event: error"), body)
            assertTrue(body.contains("invalid_request_error"), body)
            assertTrue(body.contains(STOPPED), body)
            assertFalse(body.contains("retry") || body.contains("again"), "a stop never invites a retry: $body")
            assertFalse(body.contains("message_stop"), "a stopped turn does not end as finished: $body")

            rig.until("the stopped turn's slot is released") { rig.gate.snapshot().inflight == 0 }
            // The gate counts the slot free before it runs the slot's release hooks, and one of those
            // hooks unlists the turn (InflightGate.releaseOwner), so the list empties just after.
            rig.until("the stopped turn leaves the live list") { rig.turns.list().isEmpty() }
            assertEquals(404, rig.routes.stop("codex", id).status.value, "an ended turn is not live")
            rig.until("the journal names the stop") { rig.journal.any { "turn ERROR stopped" in it } }
            rig.until("the perf row records the stop") { "error:stopped" in rig.perfRows() }
            rig.mock.releaseHold()
            assertEquals(1, rig.mock.upstreamBodies.size, "the stopped turn went upstream once")

            // Claude Code's re-send: same session, same messages, stream=false.
            val resend = rig.turn("go", stream = false)
            val refused = resend.bodyAsText()
            assertEquals(400, resend.status.value, refused)
            assertTrue(refused.contains("invalid_request_error") && refused.contains(STOPPED), refused)
            assertEquals(1, rig.mock.upstreamBodies.size, "the refused re-send never reached the upstream")

            // The mark is spent: the same request again is an ordinary turn.
            val again = rig.turn("go", stream = false)
            assertEquals(200, again.status.value, again.bodyAsText())
            assertEquals(2, rig.mock.upstreamBodies.size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a stop before the first frame is answered with the stop's own 400, never a bare 500 or a retry`(
        @TempDir tmp: Path,
    ) = runBlocking {
        // The client pinger never ticks, so no status line can open the stream before the stop lands.
        val silentPinger = HeadDeps.HeadTicks(client = Ticker { awaitCancellation() })
        val rig = Rig(tmp, seams = HeadDeps.HeadSeams(ticks = silentPinger))
        rig.start()
        try {
            val headReceived = CompletableDeferred<Int>()
            val held = async(Dispatchers.IO) { rig.streamed("go", headReceived, scenario = "holdstart") }
            rig.until("the upstream was asked and is silent, and the turn is listed") {
                rig.mock.upstreamBodies.size == 1 && rig.turns.list().size == 1
            }
            assertFalse(headReceived.isCompleted, "no frame has reached the client: the status is still the head's")

            val id = json(rig.routes.live("codex").body).getValue("turns").jsonArray.single().jsonObject
                .getValue("id").jsonPrimitive.content
            assertEquals(200, rig.routes.stop("codex", id).status.value)

            val answer = withTimeout(WAIT_MS) { held.await() }
            assertEquals(400, answer.status, "the stop's own status, never a 500 for a cancellation: ${answer.body}")
            assertEquals("false", answer.shouldRetry, "the client is told not to send it again")
            assertTrue(answer.body.contains("invalid_request_error") && answer.body.contains(STOPPED), answer.body)
            assertFalse(answer.body.contains("retry") || answer.body.contains("again"), "a stop never invites a retry")

            rig.until("the stopped turn's slot is released") { rig.gate.snapshot().inflight == 0 }
            rig.until("the stopped turn leaves the live list") { rig.turns.list().isEmpty() }
            rig.until("the journal names the stop") { rig.journal.any { "turn ERROR stopped" in it } }
            rig.until("the perf row records the stop") { "error:stopped" in rig.perfRows() }

            // The mark a stop leaves does not depend on where it landed: a re-send is refused here once.
            val resend = rig.turn("go", stream = false)
            assertEquals(400, resend.status.value, resend.bodyAsText())
            assertEquals(1, rig.mock.upstreamBodies.size, "the refused re-send never reached the upstream")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `concurrent identical requests in one session retain both identity spaces`(@TempDir tmp: Path) = runBlocking {
        val rig = Rig(tmp, WireTap(keep = 2))
        rig.start()
        try {
            val first = async(Dispatchers.IO) { rig.turn("same", stream = true).bodyAsText() }
            val second = async(Dispatchers.IO) { rig.turn("same", stream = true).bodyAsText() }
            rig.until("both requests are posted") { rig.tap!!.recent().size == 2 && rig.turns.list().size == 2 }
            val live = json(rig.routes.live("codex").body).getValue("turns").jsonArray
            val liveIds = live.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            assertEquals(2, liveIds.size)
            assertEquals(liveIds, rig.gate.snapshot().live.map { it.turnId }.toSet())
            val sent = json(rig.tap!!.json("codex")).getValue("records").jsonArray
            val sentIds = sent.map { it.jsonObject.getValue("turn_id").jsonPrimitive.content }.toSet()
            assertEquals(2, sentIds.size)
            rig.mock.releaseHold()
            withTimeout(WAIT_MS) {
                first.await()
                second.await()
            }
            assertTrue(AsyncFileIo.drain(), "the synthetic file lane drained")
            val perfIds = rig.perfRows().lineSequence().filter(String::isNotBlank)
                .map {
                    val row = json(it)
                    assertEquals(row.getValue("turn"), row.getValue("turn_id"), "captured ownership equals its trace")
                    row.getValue("turn_id").jsonPrimitive.content
                }.toSet()
            assertEquals(sentIds, perfIds)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `only the stopped session's non-stream request with the same messages is refused`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp)
        rig.start()
        try {
            val headReceived = CompletableDeferred<Int>()
            val held = async(Dispatchers.IO) { rig.streamed("go", headReceived) }
            assertEquals(200, withTimeout(WAIT_MS) { headReceived.await() }, "the client holds an open stream")
            rig.until("the turn is streaming upstream") {
                rig.gate.snapshot().live.any { it.phase == GatePhase.STREAMING }
            }
            rig.turns.stop(rig.turns.list().single().id)
            assertTrue(withTimeout(WAIT_MS) { held.await() }.body.contains(STOPPED))
            rig.mock.releaseHold()

            val streamed = rig.turn("go", stream = true)
            assertEquals(200, streamed.status.value)
            assertTrue(streamed.bodyAsText().contains("message_stop"), "a streaming request is never refused")
            val otherSession = rig.turn("go", stream = false, session = "sess-other")
            assertEquals(200, otherSession.status.value, otherSession.bodyAsText())
            val otherMessages = rig.turn("something else", stream = false)
            assertEquals(200, otherMessages.status.value, otherMessages.bodyAsText())
            assertEquals(4, rig.mock.upstreamBodies.size, "all three went upstream")

            val resend = rig.turn("go", stream = false)
            assertEquals(400, resend.status.value, "the mark outlived the requests it does not match")
            assertEquals(4, rig.mock.upstreamBodies.size)
        } finally {
            rig.close()
        }
    }
}
