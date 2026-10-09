// NEW (#406): an upstream SSE frame over our size limit leaves a wire record that says so. The client already got an
// honest error; the trace used to close the turn with an outcome tag and no words. The record now carries the label
// frame_too_large, the limit and the size the frame reached, and never the frame's content. Driven through a real
// head against the mock upstream whose scenario sends one frame past the limit.
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val MAX_EVENT_CHARS = 1024 * 1024
private const val AWAIT_MS = 30_000L
private const val POLL_MS = 50L

private class OversizedFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class HeadServerOversizedFrameTraceTest {

    @Test
    fun `an oversized upstream frame leaves a wire record naming the limit and the size, not the content`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val mock = MockChatGptUpstream()
        val traceDir = tmp.resolve("trace")
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = OversizedFakeAuth(),
                locations = ProviderLocations(baseUrl = mock.baseUrl),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        val trace = splice.head.syntheticTraceStore(ActivityDays(traceDir, "codex", 7, ownerOnly = true), "codex", 4096)
        val head = HeadServer(
            provider,
            0,
            headDeps(tmp, upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2))
                .copy(stores = headStores(tmp, trace = trace)),
        )
        head.start()
        awaitListening(head.port)
        try {
            val answer = HttpClient(CIO) {
                engine { requestTimeout = 0 }
                defaultRequest { bearerAuth("test-inference-token") }
            }.use { client ->
                client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    header("Content-Type", "application/json")
                    setBody(
                        """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                            "system":"SCENARIO:oversized_sse","messages":[{"role":"user","content":"go"}]}""",
                    )
                }.bodyAsText()
            }
            assertTrue(answer.contains("oversized streaming event"), answer)

            val turn = awaitTurnRecord(traceDir)
            val sentence = turn["failure_sentence"]?.jsonPrimitive?.content
            assertNotNull(sentence, "the oversized frame left no failure words in the wire record: $turn")
            assertTrue(sentence!!.startsWith("frame_too_large:"), sentence)
            assertTrue(sentence.contains("$MAX_EVENT_CHARS-character"), sentence)
            val size = Regex("reached (\\d+) characters").find(sentence)?.groupValues?.get(1)?.toInt()
            assertTrue(size != null && size > MAX_EVENT_CHARS, "the size the frame reached is named: $sentence")
            assertFalse(sentence.contains("xxxx"), "the frame's content never enters the record")
        } finally {
            head.stop()
            mock.stop()
        }
    }

    private suspend fun awaitTurnRecord(traceDir: Path): kotlinx.serialization.json.JsonObject =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + AWAIT_MS
            while (System.currentTimeMillis() < deadline) {
                AsyncFileIo.drain()
                val record = Files.list(traceDir).use { files ->
                    files.toList().filter { it.fileName.toString().endsWith(".jsonl") }.flatMap { Files.readAllLines(it) }
                }.map { Json.parseToJsonElement(it).jsonObject }
                    .lastOrNull { it["outcome"] != null }
                if (record != null) return@withContext record
                delay(POLL_MS)
            }
            error("no turn record reached the trace within ${AWAIT_MS}ms")
        }
}
