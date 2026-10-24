// NEW: the live listing says whether splice would carry on THIS round if it failed now (will_resume), so the console's
// Stalled card can read "Won't resume" instead of a bare Stalled. Read through the real route on a real head over a held
// upstream: true while only prose has streamed, false the moment the round has opened a tool call, false for a head
// with no re-anchor tier armed.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.turn.LiveTurns
import splice.head.turn.LiveTurnsByHead
import splice.head.turn.LiveTurnsRoutes
import splice.head.turn.LiveTurnsSource
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val WAIT_MS = 15_000L
private const val POLL_MS = 20L

private class WillResumeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-wr", "acct-wr")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class LiveTurnWillResumeTest {
    private class Rig(tmp: Path, stallReanchor: Duration) {
        val mock = MockChatGptUpstream()
        val turns = LiveTurns()
        private val routes = LiveTurnsRoutes(
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
                    auth = WillResumeAuth(),
                    locations = ProviderLocations(baseUrl = mock.baseUrl),
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds, stallReanchor),
                    loginCommand = "claudex login",
                ),
                reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            ),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 60_000, maxRetries = 1),
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
            ).let { base -> base.copy(traffic = base.traffic.copy(liveTurns = turns)) },
        )

        suspend fun start() {
            mock.resetHold()
            mock.resetToolHold()
            head.start()
            awaitListening(head.port)
        }

        suspend fun close() {
            mock.releaseHold()
            mock.releaseTool()
            head.stop()
            client.close()
            mock.stop()
        }

        suspend fun turn(): String = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-will-resume")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:holdtool",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

        /** The route's answer for the one live turn, once [ready] holds of it. */
        suspend fun live(ready: (Boolean) -> Boolean): Boolean = withTimeout(WAIT_MS) {
            while (true) {
                val listed = Json.parseToJsonElement(routes.live("codex").body).jsonObject["turns"]!!.jsonArray
                val turn = listed.singleOrNull()?.jsonObject
                val seen = turn?.get("seen_output")?.jsonPrimitive?.content == "true"
                val will = turn?.get("will_resume")?.jsonPrimitive?.content == "true"
                if (seen && ready(will)) return@withTimeout will
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }

    private object NoCompaction : HeadCompactSource {
        override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
    }

    @Test
    fun `will_resume reads true while a round has only streamed prose and false once it opens a tool call`(
        @TempDir tmp: Path,
    ): Unit = runBlocking {
        val rig = Rig(tmp, stallReanchor = 25.seconds)
        rig.start()
        try {
            val held = async(Dispatchers.IO) { rig.turn() }
            // seen_output is true after the first byte; the round has only shown prose so far
            assertEquals(true, rig.live { true }, "a round with prose only would still be continued")

            rig.mock.releaseHold() // the round now opens a tool call and holds again
            assertEquals(false, rig.live { !it }, "a round that has opened a tool call would not be continued")

            rig.mock.releaseTool()
            held.await()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `will_resume reads false for a head with no re-anchor tier armed, however plain the round`(
        @TempDir tmp: Path,
    ): Unit = runBlocking {
        val rig = Rig(tmp, stallReanchor = Duration.INFINITE)
        rig.start()
        try {
            val held = async(Dispatchers.IO) { rig.turn() }
            assertEquals(false, rig.live { true }, "no tier means a stall ends the round instead of resuming it")
            rig.mock.releaseHold()
            rig.mock.releaseTool()
            held.await()
        } finally {
            rig.close()
        }
    }
}
