// v0.4.0 FEATURES.md §7 through the REAL production path — HeadServer over HTTP to a mock upstream: the custom
// compaction text the operator configured reaches the upstream body of a compaction, and of nothing else. A head
// with no custom text sends the client's own request, byte for byte as it would have without the feature.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.compaction.CompactionConfig
import splice.core.compaction.CompactionInstructions
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.head.compaction.CompactionTail
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val TAIL = "KEEP EVERY OPEN CONSTRAINT VERBATIM"
private const val SUMMARIZER = "You are tasked with summarizing conversations for another agent."

private class TailAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-tail", "acct-tail")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** A provider whose dialect cannot place a compaction tail: the SPI default hands the turn straight back. */
private class UnplaceableTailProvider(delegate: Provider) : Provider by delegate {
    override fun withCompactionTail(turn: BuiltTurn, instructions: String): BuiltTurn = turn
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerCompactionTailTest(@param:TempDir private val root: Path) {

    private val mock = MockChatGptUpstream()
    private val heads = mutableListOf<HeadServer>()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    @AfterAll
    fun tearDown() = runBlocking {
        heads.forEach { it.stop() }
        mock.stop()
        client.close()
    }

    /** A codex head whose compaction tail is [config]; started, listening, and remembered for teardown. */
    private fun head(
        name: String,
        config: CompactionConfig,
        wrap: (Provider) -> Provider = { it },
    ): HeadServer = runBlocking {
        val dir = java.nio.file.Files.createDirectories(root.resolve(name))
        val tail = CompactionTail(CompactionInstructions(config, dir), projectFor = { null })
        val codex = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = TailAuth(),
                locations = ProviderLocations(baseUrl = mock.baseUrl),
                watchdog = WatchdogBudget(20.seconds, 20.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        val server = HeadServer(
            provider = wrap(codex),
            listenPort = 0,
            deps = headDeps(
                tmp = dir,
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
                seams = HeadDeps.HeadSeams(session = HeadDeps.SessionSeams(compactionTail = tail)),
            ),
        )
        server.start()
        awaitListening(server.port)
        heads += server
        server
    }

    /** Posts a turn whose system prompt is [system] and returns the body the upstream received for it. */
    private suspend fun upstreamBodyFor(head: HeadServer, system: String, marker: String): String {
        val before = mock.upstreamBodies.size
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-tail")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
                    """"system":"$system SCENARIO:basic $marker",""" +
                    """"messages":[{"role":"user","content":"carry on"}]}""",
            )
        }.bodyAsText()
        assertTrue(reply.contains("message_stop"), "the turn completed: $reply")
        return mock.upstreamBodies.drop(before).single().second
    }

    @Test
    fun `the configured text reaches the upstream body of a compaction and of no ordinary turn`() = runBlocking {
        val tailed = head("tailed", CompactionConfig(instructions = TAIL))

        val compaction = upstreamBodyFor(tailed, SUMMARIZER, "compaction-1")
        val ordinary = upstreamBodyFor(tailed, "You are a test.", "ordinary-1")

        assertTrue(compaction.contains(TAIL), "a compaction carries the operator's text")
        assertFalse(ordinary.contains(TAIL), "an ordinary turn never does")
    }

    @Test
    fun `with no custom text a compaction goes upstream as the client sent it, and an opt-out is the same`() =
        runBlocking {
            val plain = head("plain", CompactionConfig())
            val optedOut = head("opted-out", CompactionConfig(instructions = ""))

            val untouched = upstreamBodyFor(plain, SUMMARIZER, "compaction-2")
            val empty = upstreamBodyFor(optedOut, SUMMARIZER, "compaction-2")

            assertFalse(untouched.contains(TAIL))
            assertEquals(untouched, empty, "an empty rule opts out: the request is the client's own")
        }

    @Test
    fun `a tail the dialect cannot place is reported as not applied and never claimed`() = runBlocking {
        val unplaced = head("unplaced", CompactionConfig(instructions = TAIL)) { UnplaceableTailProvider(it) }

        val body = upstreamBodyFor(unplaced, SUMMARIZER, "compaction-3")

        assertFalse(body.contains(TAIL), "the wire carries nothing the dialect could not place")
        assertTrue(AsyncFileIo.drain(), "the compaction record must reach disk before it is read")
        val source = java.nio.file.Files.readAllLines(root.resolve("unplaced").resolve("compact.jsonl"))
            .map { Json.parseToJsonElement(it).jsonObject }
            .mapNotNull { it["instructions_source"]?.jsonPrimitive?.content }
            .single()
        assertTrue(source.endsWith("(not applied)"), "the record says the text was not applied: $source")
    }
}
