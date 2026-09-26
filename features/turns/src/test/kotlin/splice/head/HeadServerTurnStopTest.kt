// NEW: V4-319 — the operator stops a live turn, on a real head over a held upstream turn.
//
// The assertions are on WHAT THE CLIENT RECEIVES, because the contract is the client's: the stopped
// stream ends with an error frame that says the operator stopped the turn and never says "retry";
// the one stream=false re-send Claude Code makes of an errored stream (console's probe on 2.1.283:
// same session, same messages, 4.4-19.6 ms later) is answered HERE with a 400 and never reaches the
// upstream; and the mark that refuses it is spent by that one refusal. The upstream's request count
// is the proof of "never sent", read from the mock rather than from splice's own counters.
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
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.turn.LiveTurns
import splice.head.turn.LiveTurnsByHead
import splice.head.turn.LiveTurnsRoutes
import splice.head.turn.LiveTurnsSource
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val WAIT_MS = 10_000L
private const val POLL_MS = 20L
private const val SESSION = "sess-stop-319"
private const val STOPPED = "codex: the operator stopped this turn"

private class StopFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-stop", "acct-stop")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class HeadServerTurnStopTest {

    /** A real HeadServer over the mock upstream with its live turns wired to [routes] the way the
     *  control plane wires them. maxRetries = 1 so the held turn stays held rather than backing off. */
    private class Rig(private val tmp: Path) {
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
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = StopFakeAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                    loginCommand = "claudex login",
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 60_000, maxRetries = 1),
                gate = gate,
                log = { journal.add(it) },
            ).copy(liveTurns = turns),
        )

        suspend fun start() {
            mock.resetHold()
            head.start()
            awaitListening(head.port)
        }

        suspend fun close() {
            mock.releaseHold()
            head.stop()
            client.close()
            mock.stop()
        }

        /** One turn of [SESSION] asking [text], held upstream by the hold scenario until released. */
        suspend fun turn(text: String, stream: Boolean, session: String = SESSION): HttpResponse =
            client.post("http://127.0.0.1:${head.port}/v1/messages") {
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", session)
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":$stream,"max_tokens":64,
                        "system":"You are a test. SCENARIO:hold",
                        "messages":[{"role":"user","content":"$text"}]}""",
                )
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
            val held = async(Dispatchers.IO) { rig.turn("go", stream = true).bodyAsText() }
            rig.until("the turn is streaming upstream") {
                rig.gate.snapshot().live.any { it.phase == GatePhase.STREAMING }
            }

            val listed = json(rig.routes.live("codex").body)
            val turn = listed["turns"]!!.jsonArray.single().jsonObject
            assertEquals(SESSION, turn["session"]!!.jsonPrimitive.content, "$listed")
            assertEquals("gpt-5.6-sol", turn["model"]!!.jsonPrimitive.content, "$listed")
            val id = turn["id"]!!.jsonPrimitive.content

            val stop = rig.routes.stop("codex", id)
            assertEquals(200, stop.status.value, stop.body)
            assertEquals(json("""{"stopped":true,"head":"codex","session":"$SESSION"}"""), json(stop.body))

            // Bounded: a stop that cancels nothing leaves the turn held, and that must fail, not hang.
            val body = withTimeout(WAIT_MS) { held.await() }
            assertTrue(body.contains("event: error"), body)
            assertTrue(body.contains("invalid_request_error"), body)
            assertTrue(body.contains(STOPPED), body)
            assertFalse(body.contains("retry"), "a stop never invites a retry: $body")
            assertFalse(body.contains("message_stop"), "a stopped turn does not end as finished: $body")

            rig.until("the stopped turn's slot is released") { rig.gate.snapshot().inflight == 0 }
            assertEquals(emptyList<Any>(), rig.turns.list())
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
    fun `only the stopped session's non-stream request with the same messages is refused`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val rig = Rig(tmp)
        rig.start()
        try {
            val held = async(Dispatchers.IO) { rig.turn("go", stream = true).bodyAsText() }
            rig.until("the turn is streaming upstream") {
                rig.gate.snapshot().live.any { it.phase == GatePhase.STREAMING }
            }
            rig.turns.stop(rig.turns.list().single().id)
            assertTrue(withTimeout(WAIT_MS) { held.await() }.contains(STOPPED))
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
