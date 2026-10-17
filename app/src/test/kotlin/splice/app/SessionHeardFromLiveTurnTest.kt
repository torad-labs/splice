// A session is heard from while this daemon serves its turn, however long the turn runs, and at the
// moment the turn ends. Holds a real turn open on a real codex head (over the mock upstream's hold
// scenario) with the console's publisher as its events, and moves the publisher's wall clock past the
// stale window while the turn is live and after it ends.
package splice.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.UpstreamFaultPlan
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.turn.WatchdogBudget
import splice.core.util.WallClock
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.turn.LiveTurns
import splice.topology.TopologyLoader
import splice.upstream.Provider
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private const val SESSION = "sess-heard"
private const val T0 = 1_791_000_000_000L
private const val MINUTE_MS = 60_000L
private const val WAIT_MS = 10_000L
private const val POLL_MS = 20L

class SessionHeardFromLiveTurnTest {

    /** The codex provider the daemon builds from a topology, pointed at [baseUrl] and signed in through [authFile]. */
    private fun codex(root: Path, baseUrl: String, authFile: Path): Provider {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val parsed = TopologyLoader.parse(
            """
            [providers.codex]
            dialect = "openai-responses"
            base_url = "$baseUrl"
            auth = { kind = "chatgpt-oauth", file = "${authFile.toString().replace("\\", "/")}" }

            [[providers.codex.models]]
            id = "gpt-5.6-sol"
            label = "Sol"
            context_window = 272000

            [heads.claudex]
            provider = "codex"
            port = 9201
            discovery_prefix = "claude-codex--"
            pinned_model = "gpt-5.6-sol"
            """.trimIndent(),
        )
        val head = parsed.heads.getValue("claudex")
        val provider = parsed.providers.getValue(head.provider)
        return ProviderAssembly(
            paths,
            CoroutineScope(Dispatchers.Unconfined),
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
        ).buildProvider(
            ProviderBuild(
                key = "claudex",
                head = head,
                providerCfg = provider,
                catalog = provider.catalogFor(head),
                faultPlan = UpstreamFaultPlan(
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                    loginCommand = "claudex login",
                ),
                cfg = ConfigService(paths).getConfig("claudex"),
            ),
        ).provider
    }

    /** A real head on the codex provider over [mock], reporting to [publisher], its live turns in [turns], the way
     *  HeadServerFactory wires both. */
    private fun head(
        root: Path,
        mock: MockChatGptUpstream,
        publisher: ConsoleEventPublisher,
        turns: LiveTurns,
    ): HeadServer {
        val authFile = root.resolve("auth.json")
        Files.writeString(authFile, """{"tokens":{"access_token":"tok-1","account_id":"acct-1","refresh_token":"r"}}""")
        return HeadServer(
            provider = codex(root, mock.baseUrl, authFile),
            listenPort = 0,
            deps = headDeps(
                tmp = root,
                upstream = UpstreamClient(totalTimeoutMs = 60_000, maxRetries = 1),
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
                seams = HeadDeps.HeadSeams(events = publisher.forHead("claudex")),
            ).let { it.copy(traffic = it.traffic.copy(liveTurns = turns)) },
        )
    }

    /** One streaming turn of [SESSION], held upstream by the mock's hold scenario until it is released. */
    private suspend fun heldTurn(client: HttpClient, port: Int): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            bearerAuth("test-inference-token")
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:hold","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    private suspend fun until(what: String, check: () -> Boolean) {
        withTimeoutOrNull(WAIT_MS) { while (!check()) delay(POLL_MS) }
        assertTrue(check(), "timed out waiting: $what")
    }

    @Test
    fun `a session with a live turn is heard from now, and from the moment its turn ended after that`(
        @TempDir root: Path,
    ) = runBlocking {
        val mock = MockChatGptUpstream()
        val now = AtomicLong(T0)
        val publisher = ConsoleEventPublisher(clock = WallClock { now.get() })
        val turns = LiveTurns()
        publisher.liveTurns.put("claudex", turns)
        val head = head(root, mock, publisher, turns)
        val client = HttpClient(CIO) { expectSuccess = false }
        mock.resetHold()
        head.start()
        awaitListening(head.port)
        try {
            val held = async(Dispatchers.IO) { heldTurn(client, head.port) }
            until("the turn is live") { turns.list().any { it.session == SESSION } }
            assertEquals(T0, publisher.sessionsHeard()[SESSION], "heard when the turn started")

            now.set(T0 + 40 * MINUTE_MS)
            assertEquals(now.get(), publisher.sessionsHeard()[SESSION], "40 minutes into a live turn: heard from now")

            now.set(T0 + 41 * MINUTE_MS)
            mock.releaseHold()
            withTimeout(WAIT_MS) { held.await() }
            until("the turn ended, heard at its end") {
                turns.list().isEmpty() && publisher.sessionsHeard()[SESSION] == T0 + 41 * MINUTE_MS
            }

            now.set(T0 + 72 * MINUTE_MS)
            assertEquals(
                T0 + 41 * MINUTE_MS,
                publisher.sessionsHeard()[SESSION],
                "with no turn live, heard stays at the turn's end; past the window the registry reads it stale",
            )
        } finally {
            mock.releaseHold()
            head.stop()
            client.close()
            mock.stop()
        }
    }
}
