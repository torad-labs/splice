// Goal: when Muse's gateway gives up on a generation, or a turn ends while Meta is still generating it, splice's next
// request for that conversation goes to a new gateway route, so it never queues behind a generation that may still be
// running there. A conversation that meets neither keeps its one route, so its cache stays pinned, and a restarted
// daemon keeps every conversation on the route it last moved to.
//
// Oct 26, 02:37 to 02:55 CT: Meta answered a claude-muse turn 504 gateway_timeout after 184 s. splice re-sent the same
// request on the same x-meta-ai-gateway-session-id within a second, and that request and the four after it waited
// behind the generation for 18 minutes. After a turn the client aborted, the next warm turn of the session waited over
// 30 s 6 times in 33, against 2 to 3% after a normal turn. These drive a real claude-muse head, built from its topology
// the way the daemon builds it, over a local gateway that answers each request by the route it rides.
package splice.app.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.turn.WatchdogBudget
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.topology.TopologyLoader
import splice.upstream.Provider
import splice.upstream.Waiter
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

private const val MODEL = "muse-wonder-full-1.0-contributor"
private const val CONVERSATION = "sess-flashing-lenovo"
private const val CALM = "sess-calm"

// The daemon's own default for upstreamRetries.
private const val TRIES = 4
private const val TURN_WAIT_MS = 30_000L
private const val IDLE_WAIT_S = 30L
private const val IDLE_POLL_MS = 10L

class MuseGatewayRouteTest {

    @Test
    fun `a conversation starts on the route it rode before its route could move`(@TempDir root: Path) = runBlocking {
        museHead(root) { rig ->
            // SHA-256 of "splice-muse:muse:sess-flashing-lenovo" in the UUID v4 layout, the pin every claude-muse
            // conversation has carried since Sep 28: a deploy leaves a running conversation where its cache is.
            assertEquals("4c446196-0c76-432e-a078-6c0acb57815a", rig.firstRoute(CONVERSATION))
        }
    }

    @Test
    fun `after a gateway timeout the retry rides a new route and the client gets the answer`(@TempDir root: Path) =
        runBlocking {
            museHead(root) { rig ->
                val route = rig.firstRoute(CONVERSATION)
                rig.gateway.stall(route)

                val turn = rig.turn(CONVERSATION, 2)

                assertEquals(route, turn.routes.first(), "the turn is sent on its conversation's route: ${turn.routes}")
                val retry = turn.routes.getOrNull(1)
                assertNotNull(retry, "the gateway timeout is re-sent: ${turn.routes}")
                assertNotEquals(route, retry, "the re-send leaves the route Meta gave up on: ${turn.routes}")
                assertTrue(turn.answered, "the client gets the answer: ${turn.sse}")
            }
        }

    @Test
    fun `every later turn of that conversation keeps the new route`(@TempDir root: Path) = runBlocking {
        museHead(root) { rig ->
            val route = rig.firstRoute(CONVERSATION)
            rig.gateway.stall(route)
            val moved = rig.turn(CONVERSATION, 2).routes.last()
            assertNotEquals(route, moved, "the conversation left the route Meta gave up on")

            for (n in 3..4) {
                val later = rig.turn(CONVERSATION, n)
                assertEquals(listOf(moved), later.routes, "turn $n rides the route the conversation moved to")
                assertTrue(later.answered, "turn $n is answered: ${later.sse}")
            }
        }
    }

    @Test
    fun `a conversation that meets no timeout keeps one route across its turns and a restart`(@TempDir root: Path) =
        runBlocking {
            museHead(root) { rig ->
                val calm = mutableListOf<String?>()
                calm += rig.turn(CALM, 1).routes
                // Meanwhile Meta gives up on another conversation's route on the same head.
                rig.gateway.stall(rig.firstRoute(CONVERSATION))
                rig.turn(CONVERSATION, 2)
                for (n in 2..3) {
                    val turn = rig.turn(CALM, n)
                    assertTrue(turn.answered, "turn $n is answered: ${turn.sse}")
                    calm += turn.routes
                }
                rig.restart()
                val after = rig.turn(CALM, 4)
                assertTrue(after.answered, "turn 4, after the restart, is answered: ${after.sse}")
                calm += after.routes

                assertEquals(1, calm.toSet().size, "every request of the conversation rides one route: $calm")
                assertNotNull(calm.first(), "the route is named on every request: $calm")
            }
        }

    @Test
    fun `a turn the client abandons while Meta is still generating moves its conversation to a new route`(
        @TempDir root: Path,
    ) = runBlocking {
        museHead(root) { rig ->
            val route = rig.firstRoute(CONVERSATION)
            rig.gateway.generateWithoutEnd(route)
            val abandoned = rig.startTurn(CONVERSATION, 2)
            abandoned.awaitMidGeneration()

            // The user stops the turn: Claude Code closes its connection while Meta is still generating.
            abandoned.job.cancelAndJoin()
            rig.awaitTurnsEnded()

            val next = rig.turn(CONVERSATION, 3)
            val moved = next.routes.first()
            assertNotEquals(route, moved, "the next turn leaves the route Meta is still generating on: ${next.routes}")
            assertEquals(listOf(moved), next.routes, "it is sent once, on the new route: ${next.routes}")
            assertTrue(next.answered, "the next turn is answered: ${next.sse}")
            val later = rig.turn(CONVERSATION, 4)
            assertEquals(listOf(moved), later.routes, "turn 4 rides the route the conversation moved to")
            assertTrue(later.answered, "turn 4 is answered: ${later.sse}")
        }
    }

    @Test
    fun `a turn a restart cuts while Meta is still generating moves its conversation and the restarted head keeps it`(
        @TempDir root: Path,
    ) = runBlocking {
        museHead(root) { rig ->
            val route = rig.firstRoute(CONVERSATION)
            rig.gateway.generateWithoutEnd(route)
            val cut = rig.startTurn(CONVERSATION, 2)
            cut.awaitMidGeneration()

            rig.restart()

            val next = rig.turn(CONVERSATION, 3)
            val moved = next.routes.first()
            assertNotEquals(route, moved, "after the restart the turn leaves the route Meta is still generating on")
            assertEquals(listOf(moved), next.routes, "it is sent once, on the new route: ${next.routes}")
            assertTrue(next.answered, "the turn after the restart is answered: ${next.sse}")
        }
    }

    @Test
    fun `a conversation that left a timed-out route keeps its new route after a restart`(@TempDir root: Path) =
        runBlocking {
            museHead(root) { rig ->
                val route = rig.firstRoute(CONVERSATION)
                rig.gateway.stall(route)
                val moved = rig.turn(CONVERSATION, 2).routes.last()
                assertNotEquals(route, moved, "the conversation left the route Meta gave up on")

                rig.restart()

                val later = rig.turn(CONVERSATION, 3)
                assertEquals(listOf(moved), later.routes, "after the restart the conversation rides its new route")
                assertTrue(later.answered, "the turn after the restart is answered: ${later.sse}")
            }
        }

    /** A claude-muse head over a fresh [MetaGatewayUpstream], up for [block] and torn down after it. */
    private suspend fun museHead(root: Path, block: suspend (MuseRig) -> Unit) {
        val gateway = MetaGatewayUpstream()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(CIO) { expectSuccess = false }
        val rig = MuseRig(gateway, client, scope) { startHead(root, gateway, scope) }
        try {
            rig.start()
            block(rig)
        } finally {
            rig.stop()
            client.close()
            gateway.stop()
            scope.cancel()
        }
    }

    /** A claude-muse head started over [root]'s state the way the daemon starts it, with a provider of its own: what a
     *  restarted daemon builds. The daemon drains for 45 s before it cuts a turn that is still running; the cut is the
     *  same at any length, so this head cuts at once. */
    private suspend fun startHead(root: Path, gateway: MetaGatewayUpstream, scope: CoroutineScope): HeadServer {
        val deps = headDeps(
            tmp = root,
            upstream = UpstreamClient(
                totalTimeoutMs = 60_000L,
                maxRetries = TRIES,
                pacing = RetryPacing(waiter = Waiter { }),
            ),
        )
        val head = HeadServer(
            provider = provider(root, gateway, scope),
            listenPort = 0,
            deps = deps.copy(policy = HeadDeps.HeadPolicy(stopDrainMs = 0)),
        )
        head.start()
        awaitListening(head.port)
        return head
    }

    /** The Muse provider the daemon builds for a claude-muse head pointed at [gateway], signed in with a minted key.
     *  The key mint is never reached: every answer here is a gateway's, never a refused key's. */
    private fun provider(root: Path, gateway: MetaGatewayUpstream, scope: CoroutineScope): Provider {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val authFile = root.resolve("muse-auth.json")
        Files.writeString(authFile, """{"access_token":"account-primary","api_key":"key-primary"}""")
        val topology = TopologyLoader.parse(
            """
            [providers.muse]
            dialect = "openai-responses"
            base_url = "${gateway.baseUrl}"
            auth = { kind = "muse-oauth", file = "${authFile.toString().replace("\\", "/")}" }

            [[providers.muse.models]]
            id = "$MODEL"
            context_window = 1000000

            [heads.claude-muse]
            provider = "muse"
            port = 31393
            discovery_prefix = "claude-muse--"
            pinned_model = "$MODEL"
            """.trimIndent(),
        )
        val head = topology.heads.getValue("claude-muse")
        val muse = topology.providers.getValue("muse")
        val log = LogSink { }
        val noMint = MuseKeyMintCall { _, _ -> MuseMintAttempt.Denied("no mint in this test") }
        return ProviderAssembly(
            paths,
            scope,
            log = log,
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
            museArm = MuseResponsesArm(paths, log, scope, noMint),
        ).buildProvider(
            ProviderBuild(
                key = "claude-muse",
                head = head,
                providerCfg = muse,
                catalog = muse.catalogFor(head),
                faultPlan = UpstreamFaultPlan(
                    watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                    loginCommand = "claude-muse login",
                ),
                cfg = ConfigService(paths).getConfig("claude-muse"),
            ),
        ).provider
    }
}

/** What one turn looked like from both ends: the routes the gateway saw it ride, and what the client was streamed. */
private data class SeenTurn(val routes: List<String?>, val sse: String) {
    private val events: List<JsonObject> = sse.lineSequence()
        .filter { it.startsWith("data: ") }
        .mapNotNull { JsonScalars.objectOrNull(Json, it.removePrefix("data: ")) }
        .toList()

    private val text: String = events
        .filter { JsonScalars.str(it, "type") == "content_block_delta" }
        .mapNotNull { JsonScalars.str(it["delta"] as? JsonObject, "text") }
        .joinToString("")

    /** The client was streamed Meta's answer, and its stream ended with message_stop, never an error. */
    val answered: Boolean
        get() = META_ANSWER in text &&
            events.any { JsonScalars.str(it, "type") == "message_stop" } &&
            events.none { JsonScalars.str(it, "type") == "error" }
}

/** A turn the client is still reading: [job] is the client's request, and [midGeneration] completes once the client has
 *  been streamed the start of Meta's answer. */
private class StreamingTurn(val job: Job, private val midGeneration: CompletableDeferred<Unit>) {
    suspend fun awaitMidGeneration() {
        withTimeout(TURN_WAIT_MS) { midGeneration.await() }
    }
}

/** Starts a claude-muse head, as the daemon does on boot and again on a restart. */
private fun interface HeadStart {
    suspend fun start(): HeadServer
}

/** A running claude-muse head, the gateway behind it, and a client that speaks to it the way Claude Code does. */
private class MuseRig(
    val gateway: MetaGatewayUpstream,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val heads: HeadStart,
) {
    private var head: HeadServer? = null

    private val running: HeadServer get() = checkNotNull(head) { "no head is running" }

    suspend fun start() {
        head = heads.start()
    }

    /** The daemon restarts: the head stops, cutting any turn still running, and a new one starts over the same
     *  state. */
    suspend fun restart() {
        stop()
        start()
    }

    suspend fun stop() {
        head?.stop()
        head = null
    }

    /** Turn [n] of [session]: every earlier question and its answer, then the next question, as Claude Code sends
     *  it. */
    suspend fun turn(session: String, n: Int): SeenTurn {
        val before = gateway.routes.size
        val sse = withTimeout(TURN_WAIT_MS) {
            client.post(messagesUrl()) { ask(session, n) }.bodyAsText()
        }
        return SeenTurn(gateway.routes.drop(before), sse)
    }

    /** Turn [n] of [session], read as it streams, the way Claude Code reads a turn the user may stop. */
    fun startTurn(session: String, n: Int): StreamingTurn {
        val midGeneration = CompletableDeferred<Unit>()
        val url = messagesUrl()
        val job = scope.launch {
            client.preparePost(url) { ask(session, n) }.execute { response ->
                val body = response.bodyAsChannel()
                while (true) {
                    val line = body.readLine() ?: break
                    if (META_PARTIAL in line) midGeneration.complete(Unit)
                }
            }
        }
        return StreamingTurn(job, midGeneration)
    }

    /** Turn 1 of [session], answered on the route the conversation starts on, and that route. */
    suspend fun firstRoute(session: String): String {
        val turn = turn(session, 1)
        assertTrue(turn.answered, "turn 1 is answered: ${turn.sse}")
        return requireNotNull(turn.routes.singleOrNull()) { "turn 1 rides one named route: ${turn.routes}" }
    }

    /** The head has ended every turn it was running, as its in-flight count shows. Polls the count with a deadline,
     *  never a sleep for a duration (kt-tests-no-wall-clock). */
    fun awaitTurnsEnded() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IDLE_WAIT_S)
        while (running.healthSnapshot().gate.inflight > 0) {
            check(System.nanoTime() < deadline) { "the head still runs a turn after $IDLE_WAIT_S s" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(IDLE_POLL_MS))
        }
    }

    private fun messagesUrl(): String = "http://127.0.0.1:${running.port}/v1/messages"

    private fun HttpRequestBuilder.ask(session: String, n: Int) {
        bearerAuth("test-inference-token")
        header("Content-Type", "application/json")
        header("x-claude-code-session-id", session)
        setBody("""{"model":"claude-muse--$MODEL","stream":true,"max_tokens":64,"messages":[${history(n)}]}""")
    }

    private fun history(n: Int): String = (1..n).joinToString(",") { k ->
        val question = """{"role":"user","content":"question $k"}"""
        if (k < n) """$question,{"role":"assistant","content":"$META_ANSWER"}""" else question
    }
}
