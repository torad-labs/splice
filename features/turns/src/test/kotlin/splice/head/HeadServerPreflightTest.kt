// NEW: V4-446 — HTTP preflight overflow beside the existing SSE overflow integration test.
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.CodexCompactionReserves
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.head.wire.TraceStore
import splice.upstream.ProviderTuning
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class PreflightAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token", "synthetic-account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class HeadServerPreflightTest {
    private fun head(root: Path, upstream: MockChatGptUpstream): HeadServer {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
            pinnedModel = "gpt-5.6-sol",
            compactionReserveDefaults = CodexCompactionReserves,
        )
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = PreflightAuth(),
                baseUrl = upstream.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        val trace = TraceStore(ActivityDays(root.resolve("trace"), "codex", 7, ownerOnly = true), "codex", 4_096)
        val stores = headStores(root, suffix = "-preflight").copy(trace = trace)
        return HeadServer(provider, 0, headDeps(root).copy(stores = stores))
    }

    private suspend fun send(
        client: HttpClient,
        port: Int,
        previous: String,
        content: String,
        system: String = "test",
    ): HttpResponse = client.post("http://127.0.0.1:$port/v1/messages") {
        header("Content-Type", "application/json")
        header("x-claude-code-session-id", "preflight-session")
        val body = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,"system":"$system","messages":[""" +
            previous + """{"role":"user","content":"$content"}]}"""
        setBody(body)
    }

    private suspend fun compact(client: HttpClient, port: Int, previous: String, content: String): HttpResponse =
        send(client, port, previous, content, "SCENARIO:basic tasked with summarizing conversations")

    private fun recorded(root: Path): String {
        check(AsyncFileIo.drain()) { "trace writes must settle" }
        return Files.list(root.resolve("trace")).use { paths ->
            paths.filter { it.toString().endsWith(".jsonl") }.map(Files::readString).toList().joinToString("\n")
        }
    }

    @Test
    fun `preflight overflow returns 400 and backend overflow stays an SSE error`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) {
            engine { requestTimeout = 0 }
            defaultRequest { bearerAuth("test-inference-token") }
        }
        val server = head(root, upstream)
        try {
            server.start()
            val nearLimit = "x".repeat(737_000)
            val previous = """{"role":"assistant","content":"earlier"},"""
            val first = send(client, server.port, "", nearLimit)
            assertEquals(200, first.status.value, "a first exchange below W must not trigger an impossible compact")
            first.bodyAsText()
            val ordinary = send(client, server.port, previous, nearLimit)
            assertEquals(400, ordinary.status.value, "ordinary growth above W−R must compact before SSE")
            val refusal = ordinary.bodyAsText()
            assertTrue("prompt is too long" in refusal, refusal)
            assertTrue("estimate basis local-bytes-3" in refusal, refusal)
            val compact = compact(client, server.port, previous, nearLimit + "summary")
            assertEquals(200, compact.status.value, "the prompted compaction fits W−generation")
            assertTrue("event: message_stop" in compact.bodyAsText())
            val tooLargeFirst = send(client, server.port, "", "x".repeat(820_000))
            assertEquals(400, tooLargeFirst.status.value, "first exchange above W gets an honest 400")
            tooLargeFirst.bodyAsText()
            val tooLargeCompact = compact(client, server.port, previous, "x".repeat(760_000))
            assertEquals(400, tooLargeCompact.status.value, "a compaction above W−generation must not post")
            tooLargeCompact.bodyAsText()
            assertEquals(2, upstream.upstreamBodies.size, "only the fitting first and compaction reached upstream")
            val overflow = send(client, server.port, "", "go", "SCENARIO:overflow_sse")
            assertEquals(200, overflow.status.value, "a real in-stream backend error keeps its committed 200")
            val sse = overflow.bodyAsText()
            assertTrue("event: error" in sse && "prompt is too long" in sse, sse)
            assertEquals(3, upstream.upstreamBodies.size, "only fitting requests and backend overflow sent upstream")
            val recorded = recorded(root)
            assertTrue("compaction-preflight-first-exchange" in recorded, recorded)
            assertTrue("compaction-preflight-compactable" in recorded, recorded)
            assertTrue("compaction-preflight-compact-overflow" in recorded, recorded)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }
}
