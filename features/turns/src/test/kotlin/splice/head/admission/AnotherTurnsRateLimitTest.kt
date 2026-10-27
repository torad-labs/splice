// A turn waits only on its own failure, never on another turn's (Marcos, Oct 26, approving the login-hold fix: "your 3
// fixes sounds reasonabel. I KNEW IT WEAS THE CODE"). Until then, a turn that ran out of retries on a 429 put its login
// on hold, and every later turn on that login slept before it was sent or was refused without reaching the provider.
// On claude-splice, Oct 25 between 6 and 11 PM CT, the ten slowest such turns waited 45 s to 2.3 min and then failed
// with the rate-limit error anyway. Each turn now asks the provider itself and gets the provider's own answer.
package splice.head.admission

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.TestResponsesProvider
import splice.head.headDeps
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class OneLoginAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-one", "acct-one")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** A real head on one login over a mock provider. One try per turn, so a 429 is the turn's last word and its give-up
 *  holds the login, the way a turn that ran out of retries does. */
private class OneLoginHead(tmp: Path) {
    val provider = MockChatGptUpstream()
    val upstream = UpstreamClient(totalTimeoutMs = 30_000)
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val head = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = OneLoginAuth(),
                locations = ProviderLocations(baseUrl = provider.baseUrl),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        ),
        listenPort = 0,
        deps = headDeps(tmp = tmp, upstream = upstream, gate = InflightGate(maxInflight = { 2 }), log = {}),
    )

    suspend fun start() {
        head.start()
    }

    suspend fun close() {
        head.stop()
        client.close()
        provider.stop()
    }

    /** What reached the provider, in order: each request's scenario, "basic" for a plain question. */
    fun reachedProvider(): List<String> = provider.upstreamBodies.map { it.first }

    /** A streamed turn; [scenario] tells the mock provider how to answer it, null for a plain answer. */
    suspend fun turn(scenario: String?): HttpResponse = client.post("http://127.0.0.1:${head.port}/v1/messages") {
        header("Content-Type", "application/json")
        val system = scenario?.let { ""","system":"You are a test. SCENARIO:$it"""" } ?: ""
        setBody(
            """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64$system,
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }

    /** The provider limits the login for a minute, and the turn that meets it gives up and holds the login. Returns
     *  what that turn's client got: the status and body a provider's refusal reaches a streamed turn as. */
    suspend fun limitTheLogin(): Pair<Int, String> {
        val first = turn("quota429")
        val body = first.bodyAsText()
        // POSITIVE FIRST: the scenario is the one these tests mean, or what follows proves nothing.
        assertEquals(listOf("quota429"), reachedProvider(), "precondition: the first turn reached the provider")
        assertTrue(upstream.rateLimitedForMs > 0L, "precondition: the first turn's give-up holds the login")
        return first.status.value to body
    }
}

/** The type of the error a body carries, as the client reads it. */
private fun errorType(body: String): String? =
    Regex(""""error":\{"type":"(\w+)"""").find(body)?.groupValues?.get(1)

class AnotherTurnsRateLimitTest {

    @Test
    fun `a turn after another turn gave up on a rate limit is sent to the provider and gets its answer`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val head = OneLoginHead(tmp)
        head.start()
        try {
            head.limitTheLogin()

            val next = head.turn(null)
            val body = next.bodyAsText()

            assertEquals(listOf("quota429", "basic"), head.reachedProvider(), "the next turn is sent, not held: $body")
            assertEquals(200, next.status.value, "the provider answered it, so its client gets that answer: $body")
            assertTrue("ok after auth" in body, "the provider's own words reach the client: $body")
        } finally {
            head.close()
        }
    }

    @Test
    fun `a turn on a login the provider still limits is sent and gets the provider's own refusal`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val head = OneLoginHead(tmp)
        head.start()
        try {
            val (firstStatus, firstBody) = head.limitTheLogin()

            val next = head.turn("quota429")
            val body = next.bodyAsText()

            assertEquals(
                listOf("quota429", "quota429"),
                head.reachedProvider(),
                "the provider answers each turn itself, so a lifted limit is never missed: $body",
            )
            assertEquals(firstStatus, next.status.value, "its refusal reaches the client as the first one did: $body")
            assertEquals(errorType(firstBody), errorType(body), "with the same error: $body")
            // The head's line for a usage limit (failure-handling slice 2), as the first turn's was.
            assertTrue("is at its usage limit" in body, "the client is told it is a rate limit: $body")
            assertTrue("holding" !in body, "never splice's own hold: $body")
        } finally {
            head.close()
        }
    }
}
