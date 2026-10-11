// NEW: V4-444 — a request sent on an API key records WHICH key sent it, so the console's Requests list can name a
// key command's account and Usage can split its spend. Before this, every row of every key head read
// `account: "primary"`: one bare word for OpenRouter, DeepSeek and the rest, unchanged when the key was rotated.
//
// Driven through a real head over a local upstream, because the claim is about the row the perf file holds, not
// about a label passed between two objects. The second turn runs after the key behind the variable changed, which
// is the case the console needs: a rotated key is its own account and the one it replaced keeps its history.
package splice.head.admission

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.TestResponsesProvider
import splice.head.headDeps
import splice.head.headStores
import splice.head.perf.PerfStats
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val KEY_VARIABLE = "OPENROUTER_API_KEY"
private const val FIRST_PRINT = "3f9a1b2c"
private const val ROTATED_PRINT = "88d4e701"

/** A key auth as the providers' own describes itself: the variable it reads and a short digest of the key's value,
 *  never the value. [print] is what the head would publish after a `splice key set` replaced the key. */
private class KeyAuth(@Volatile var print: String?) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("secret-value")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(
        present = print != null,
        kind = "api-key",
        fields = buildMap {
            put("env_var", KEY_VARIABLE)
            put("key_source", "environment")
            print?.let { put("key_fingerprint", it) }
        },
    )
}

/** One key head with no login pool, over the local upstream, writing its rows to a perf file this test reads. */
private class KeyHead(tmp: Path, val auth: KeyAuth) {
    private val mock = MockChatGptUpstream()
    private val perfFile: Path = tmp.resolve("perf.jsonl")
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val head = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "openrouter", label = "openrouter"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-openrouter--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = auth,
                locations = ProviderLocations(baseUrl = mock.baseUrl),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "splice key set $KEY_VARIABLE",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
            log = {},
        ).let { it.copy(stores = headStores(tmp).copy(perfStats = PerfStats(perfFile))) },
    )

    suspend fun start() = head.start()

    suspend fun close() {
        head.stop()
        client.close()
        mock.stop()
    }

    suspend fun turn(): String = client.post("http://127.0.0.1:${head.port}/v1/messages") {
        header("Content-Type", "application/json")
        setBody(
            """{"model":"claude-openrouter--gpt-5.6-sol","stream":true,"max_tokens":64,
                "system":"You are a test. SCENARIO:basic",
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }.bodyAsText()

    /** The account each row of the perf file names, oldest first. */
    fun accounts(): List<String?> {
        assertTrue(AsyncFileIo.drain())
        return Files.readString(perfFile).lineSequence().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .map { row: JsonObject -> row["account"]?.jsonPrimitive?.content }
            .toList()
    }
}

class KeyAccountTurnTest {
    @Test
    fun `a key head's rows name the key that sent them, and a rotated key is its own account`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val auth = KeyAuth(FIRST_PRINT)
        val head = KeyHead(tmp, auth)
        head.start()
        val accounts = try {
            head.turn()
            auth.print = ROTATED_PRINT // `splice key set OPENROUTER_API_KEY` landed between the two turns
            head.turn()
            head.accounts()
        } finally {
            head.close()
        }

        assertEquals(
            listOf("$KEY_VARIABLE:$FIRST_PRINT", "$KEY_VARIABLE:$ROTATED_PRINT"),
            accounts,
            "each row names the key in use when it was sent, so Requests and Usage can split a key command by key",
        )
    }

    /** A key head whose key is gone records NO account. "primary" there was a label with nothing behind it, which
     *  is the thing row 32 set out to remove, and the turn ends auth-missing anyway. */
    @Test
    fun `a head whose key is missing records no account, rather than a made-up one`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val head = KeyHead(tmp, KeyAuth(print = null))
        head.start()
        val accounts = try {
            head.turn()
            head.accounts()
        } finally {
            head.close()
        }

        assertEquals(listOf(null), accounts, "no key to name is no account: a row never invents one")
    }
}
