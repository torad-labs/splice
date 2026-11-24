// NEW: gateway-no-heads. ProviderRuntime serves a turn with no head anywhere in its construction (no HeadConfig, no
// ProviderBuild, no bound port): ScriptedHeads.headless builds it the way ScriptedHeads builds a HeadServer, minus the
// port. The call a test posts carries a body the provider does not serve; only the GatewayTurn's body is the one a turn
// runs on, which is what the gateway does (it reads the call's body once, to route, and rewrites it). The call also
// carries a key of the gateway's own, which opens the gateway and nothing else: the provider must never see it.
package splice.head

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.server.ServedTurn
import splice.core.server.ServedTurns
import splice.core.topology.ForwardHeaders
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedLogin
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class ProviderRuntimeTest {
    /** What the gateway's caller read back. */
    private data class Answer(val status: Int, val shouldRetry: String?, val text: String)

    private fun body(model: String) =
        """{"model":"$model","max_tokens":64,"stream":false,"messages":[{"role":"user","content":"hello"}]}"""

    /** The call a gateway received: it names a model no provider serves, so only the [GatewayTurn] can be served. */
    private val received = body("claude-nobody--not-served")

    private fun chatTurn(door: String) =
        GatewayTurn(body("claude-scripted-headless-chat--scripted-model").toByteArray(), door, null)

    private fun claudeTurn() =
        GatewayTurn(body("claude-scripted-headless-claude--scripted-model").toByteArray(), "claude", null)

    /** The key the gateway's own door took, in both spellings a caller sends one in. */
    private val gatewayKey = "gateway-door-key-0123456789"

    /** Posts [received], with the gateway's key on it, to a route that hands [runtime] [turn]. */
    private suspend fun post(
        runtime: ProviderRuntime,
        turn: GatewayTurn,
        extra: Map<String, String> = emptyMap(),
    ): Answer {
        var answer: Answer? = null
        testApplication {
            routing { post("/v1/messages") { runtime.serve(call, turn) } }
            val response = createClient { }.post("/v1/messages") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $gatewayKey")
                header("x-api-key", gatewayKey)
                extra.forEach { (name, value) -> header(name, value) }
                setBody(received)
            }
            answer = Answer(response.status.value, response.headers["x-should-retry"], response.bodyAsText())
        }
        return checkNotNull(answer)
    }

    /** Whatever key the gateway's door took, the provider saw none of it, in any header or in the body. */
    private fun assertSawNoGatewayKey(requests: List<ProviderRequest>) {
        assertTrue(requests.isNotEmpty(), "the provider received no request to read")
        requests.forEach { request ->
            val leaked = request.headers.filterValues { gatewayKey in it }.keys
            assertTrue(leaked.isEmpty(), "the gateway's key rode upstream in $leaked")
            assertFalse(gatewayKey in request.body, "the gateway's key rode upstream in the body")
        }
    }

    @Test
    fun `a turn served with no head runs on the gateway's body and answers`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered with no head") })
            val runtime = headless.chat().runtime

            val answer = post(runtime, chatTurn("claude"))

            assertEquals(200, answer.status, answer.text)
            assertTrue(provider.requests.any { it.path == "/chat/completions" }, "the mock never saw the turn")
            assertTrue("answered with no head" in answer.text, answer.text)
        }
    }

    /** The `input_tokens` [answer] reports to the client. */
    private fun reportedInput(answer: Answer): Long =
        Json.parseToJsonElement(answer.text).jsonObject.getValue("usage").jsonObject
            .getValue("input_tokens").jsonPrimitive.content.toLong()

    @Test
    fun `a gateway turn's counts scale to the window its gateway read off the request`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val runtime = headless.chat().runtime
            val beta = mapOf("anthropic-beta" to "context-1m-2025-08-07")
            // The scripted row declares 200,000 and its provider reports 12 input tokens. The door weighed this
            // request's 1M beta against a configured 100,000 window (splice.toml's `[desktop]`), so its counts
            // scale by 100,000/200,000 — not by the beta's own million, which would report 60.
            val configured = GatewayTurn(
                body("claude-scripted-headless-chat--scripted-model").toByteArray(),
                "desktop",
                100_000,
                requestWindow = 100_000,
            )
            assertEquals(6L, reportedInput(post(runtime, configured, beta)))
            // A window handed over as a session's alone still yields to the beta, as a terminal turn's does.
            val handed = GatewayTurn(
                body("claude-scripted-headless-chat--scripted-model").toByteArray(),
                "claude",
                100_000,
            )
            assertEquals(60L, reportedInput(post(runtime, handed, beta)))
        }
    }

    @Test
    fun `the door the gateway names is the door the turn's row says`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val headless = headless.chat()

            post(headless.runtime, chatTurn("claude"))

            val row = awaitPerfRow(headless.perfFile) { "\"door\":\"claude\"" in it }
            assertNotNull(row, "no perf row names the door: ${perfRows(headless.perfFile)}")
        }
    }

    @Test
    fun `a body over the limit is refused with the size answer, not served`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            // The cap is smaller than the body the gateway hands over, so it meets the limit a head's own read would.
            val runtime = headless.chat(maxRequestBytes = chatTurn("claude").body.size - 1).runtime

            val answer = post(runtime, chatTurn("claude"))

            assertEquals(413, answer.status, answer.text)
            assertTrue(provider.requests.none { it.path == "/chat/completions" }, "an oversized turn reached the mock")
        }
    }

    @Test
    fun `a provider holding its own login sends that login and never the key the gateway's door took`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val runtime = headless.chat().runtime

            val answer = post(runtime, chatTurn("desktop"))

            assertEquals(200, answer.status, answer.text)
            assertSawNoGatewayKey(provider.requests)
            assertEquals("Bearer ${ScriptedLogin.FIRST}", provider.requests.single().headers["authorization"])
        }
    }

    @Test
    fun `naming the credential in forward_headers does not carry the gateway's key upstream either`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val runtime = headless.chat(forwarded = ForwardHeaders(listOf("Authorization", "x-api-key"))).runtime

            val answer = post(runtime, chatTurn("desktop"))

            assertEquals(200, answer.status, answer.text)
            assertSawNoGatewayKey(provider.requests)
            assertEquals("Bearer ${ScriptedLogin.FIRST}", provider.requests.single().headers["authorization"])
        }
    }

    @Test
    fun `a provider that forwards a login runs the gateway's turn on an account added to splice`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { anthropicAnswer("answered") })
            val runtime = headless.claude(accounts = listOf("acct-a")).runtime

            val answer = post(runtime, claudeTurn())

            assertEquals(200, answer.status, answer.text)
            assertSawNoGatewayKey(provider.requests)
            assertEquals("Bearer ${ScriptedLogin.of("acct-a")}", provider.requests.single().headers["authorization"])
        }
    }

    @Test
    fun `with no account added, a provider that forwards a login says it has none and sends nothing`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { anthropicAnswer("answered") })
            val runtime = headless.claude().runtime

            val answer = post(runtime, claudeTurn())

            assertEquals(403, answer.status, answer.text)
            assertEquals("false", answer.shouldRetry, "a login this machine lacks is not re-sent")
            assertTrue(provider.requests.isEmpty(), "a turn with no login of its own was sent: ${provider.requests}")
            assertFalse(gatewayKey in answer.text, "the refusal quoted the gateway's key")
        }
    }

    /** What the board was told: the ids a turn ended or held under. */
    private class RecordedBoard : ServedTurns {
        val ended = CopyOnWriteArrayList<String>()
        val held = CopyOnWriteArrayList<String>()

        override fun ended(id: String, turn: ServedTurn) {
            ended += id
        }

        override fun held(id: String) {
            held += id
        }
    }

    /** The headers a team server sets on a turn it relays, which only the server may set. */
    private val relayHeaders = mapOf(
        "x-splice-served-turn" to "someone-elses-row",
        "x-splice-upstream" to "someone-elses-server",
        "x-splice-door" to "network",
    )

    @Test
    fun `a caller's served-turn id on a gateway turn names no ledger row`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val board = RecordedBoard()
            val headless = headless.chat(served = board)

            val answer = post(headless.runtime, chatTurn("claude"), relayHeaders)

            assertEquals(200, answer.status, answer.text)
            val row = awaitPerfRow(headless.perfFile) { "\"door\":\"claude\"" in it }
            assertNotNull(row, "the turn wrote no row naming the door the gateway set: ${perfRows(headless.perfFile)}")
            assertTrue(board.ended.isEmpty() && board.held.isEmpty(), "a caller's id reached the board: ${board.ended}")
        }
    }

    @Test
    fun `the served-turn id the gateway authenticated is the one the board is told`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val board = RecordedBoard()
            val headless = headless.chat(served = board)
            val turn = GatewayTurn(
                body("claude-scripted-headless-chat--scripted-model").toByteArray(),
                "network",
                null,
                listener = ListenerSays(servedTurn = "the-servers-row"),
            )

            post(headless.runtime, turn, relayHeaders)

            awaitPerfRow(headless.perfFile) { "\"door\":\"network\"" in it }
            assertEquals(listOf("the-servers-row"), board.ended)
        }
    }

    @Test
    fun `a caller's notice header does not stand in for the turn on a gateway call`(@TempDir tmp: Path) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered by the provider") })
            val runtime = headless.chat().runtime

            val answer = post(runtime, chatTurn("claude"), mapOf("x-splice-served-notice" to "forged notice"))

            assertEquals(200, answer.status, answer.text)
            assertTrue("answered by the provider" in answer.text, answer.text)
            assertFalse("forged notice" in answer.text, "the caller's notice was served as the answer: ${answer.text}")
            assertTrue(provider.requests.isNotEmpty(), "the turn never reached the provider")
        }
    }

    @Test
    fun `a forwarded header the gateway found carrying its key is not sent upstream, and a clean one is`(
        @TempDir tmp: Path,
    ) = runTest {
        withScriptedHeads(tmp) {
            provider.script(reply { chatAnswer("answered") })
            val runtime = headless.chat(forwarded = ForwardHeaders(listOf("x-team-key", "x-trace-id"))).runtime
            val turn = GatewayTurn(
                body("claude-scripted-headless-chat--scripted-model").toByteArray(),
                "terminal",
                null,
                withheldHeaders = setOf("x-team-key"),
            )

            val answer = post(runtime, turn, mapOf("x-team-key" to gatewayKey, "x-trace-id" to "trace-1"))

            assertEquals(200, answer.status, answer.text)
            assertSawNoGatewayKey(provider.requests)
            val sent = provider.requests.single().headers
            assertEquals("trace-1", sent["x-trace-id"], "a header with no key was dropped")
        }
    }
}
