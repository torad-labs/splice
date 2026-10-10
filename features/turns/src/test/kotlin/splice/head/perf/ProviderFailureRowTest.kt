// NEW: V4-444 — a failed request's row says what the PROVIDER answered: its HTTP status, and its own words.
//
// Before this the row carried splice's outcome tag and cause, both splice's reading of the failure, so the console's
// Requests list could show that a request failed but never what came back. The two facts are the provider's, so the
// row must not carry a gateway sentence under them: a hold that stood in for the upstream never asked it, and the
// classifier's own rewrite (ForeignCredential, V4-242) prefixes a clause splice wrote for the CLIENT.
//
// Also the capture flag: whether prompts and answers were saved for this turn, said outright on every row, because a
// surface cannot tell "no capture was kept" from "the id is missing" out of an absent trace id.
//
// Driven through a real head over a local upstream that answers what the vendors answer, reading the perf file back,
// because the claim is about the row on disk.
package splice.head.perf

import com.sun.net.httpserver.HttpServer
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.TestResponsesProvider
import splice.head.headDeps
import splice.head.headStores
import splice.head.syntheticTraceStore
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val SERVICE_UNAVAILABLE = 503
private const val UNAUTHORIZED = 401
private const val TRACE_DAY_EPOCH_MS = 1_789_725_600_000L

// The token the head sends upstream. A provider that echoes a request header into its error body would carry it
// onto the console, so the row's words must not hold it.
private const val SENT_TOKEN = "sk-live-9f3a8c1d4e7b20556677"

// What a model host answers when it is out of capacity: its own sentence, which is the one the row must carry.
private const val OVERLOADED_WORDS = "Model host is out of capacity; try again shortly"

private fun overloadedBody() = """{"error":{"type":"server_error","message":"$OVERLOADED_WORDS"}}"""

// A 401 naming a DIFFERENT masked key: the ForeignCredential case (V4-242). The client is told a sentence splice
// wrote; the row is told what the upstream said.
private const val FOREIGN_WORDS = "Incorrect API key provided: sk-svcac****fvMA"

private fun foreignKeyBody() = """{"error":{"type":"invalid_request_error","message":"$FOREIGN_WORDS"}}"""

// A 401 that echoes the credential the head sent back into its own sentence.
private fun echoedKeyBody() =
    """{"error":{"type":"invalid_request_error","message":"token $SENT_TOKEN is not authorized"}}"""

// A sentence that QUOTES the request back, as the vendors do for a bad field. Its own spelling in the body is
// escaped, so a reading that only looked for the plain text would drop it.
private const val QUOTING_WORDS = """Invalid value for 'model': "gpt-9" is not available"""

private const val QUOTING_BODY =
    """{"error":{"type":"invalid_request_error","message":"Invalid value for 'model': \"gpt-9\" is not available"}}"""

private class SentTokenAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(SENT_TOKEN, "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** A real head over a local upstream that answers every request with one status and body. [keepsCapture] decides
 *  whether the head has a trace store at all, which is what the row's capture flag reports. */
private class FailingHead(tmp: Path, status: Int, body: String, keepsCapture: Boolean = false) {
    private val upstream: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val perfFile: Path = tmp.resolve("perf.jsonl")
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val trace = if (!keepsCapture) {
        null
    } else {
        syntheticTraceStore(
            ActivityDays(tmp.resolve("trace"), "openai", 7, WallClock { TRACE_DAY_EPOCH_MS }, ownerOnly = true),
            "openai",
            maxBodyChars = 4096,
            now = WallClock { TRACE_DAY_EPOCH_MS },
        )
    }
    val recording = trace?.recording
    private val head = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "openai", label = "openai"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-openai--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = SentTokenAuth(),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
            log = {},
        ).let { it.copy(stores = headStores(tmp, trace = trace).copy(perfStats = PerfStats(perfFile))) },
    )

    suspend fun start() = head.start()

    suspend fun close() {
        head.stop()
        client.close()
        upstream.stop(0)
    }

    suspend fun turn(): String = client.post("http://127.0.0.1:${head.port}/v1/messages") {
        header("Content-Type", "application/json")
        setBody(
            """{"model":"claude-openai--gpt-5.6-sol","stream":true,"max_tokens":64,
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }.bodyAsText()

    /** Every row this head's turns wrote, oldest first. */
    fun rows(): List<JsonObject> {
        assertTrue(AsyncFileIo.drain())
        return Files.readString(perfFile).lineSequence().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }.toList()
    }

    /** The one row this head's turn wrote. */
    fun row(): JsonObject {
        val rows = rows()
        assertEquals(1, rows.size, "one turn, one row: $rows")
        return rows.single()
    }
}

/** One turn against an upstream that answers [status] and [body], and the row it wrote. */
private fun rowOf(tmp: Path, status: Int, body: String, keepsCapture: Boolean = false): JsonObject = runBlocking {
    val head = FailingHead(tmp, status, body, keepsCapture)
    head.start()
    try {
        head.turn()
        head.row()
    } finally {
        head.close()
    }
}

private fun JsonObject.providerStatus(): Long? = get("provider_status")?.jsonPrimitive?.longOrNull

private fun JsonObject.providerMessage(): String? = get("provider_message")?.jsonPrimitive?.content

private fun JsonObject.capture(): Boolean? = get("capture")?.jsonPrimitive?.booleanOrNull

class ProviderFailureRowTest {

    @Test
    fun `a failed request records the status the provider answered and the provider's own words`(
        @TempDir tmp: Path,
    ) {
        val row = rowOf(tmp, SERVICE_UNAVAILABLE, overloadedBody())

        assertEquals(
            SERVICE_UNAVAILABLE.toLong(),
            row.providerStatus(),
            "the status the provider answered, which the outcome tag alone never said: $row",
        )
        assertEquals(
            OVERLOADED_WORDS,
            row.providerMessage(),
            "the provider's sentence, lifted from its own body: $row",
        )
    }

    /** The ForeignCredential rewrite is splice's sentence for the CLIENT, and the row is labelled as the upstream
     *  speaking. Reading the post-rewrite message here would put a gateway clause under that label. */
    @Test
    fun `the row carries what the upstream said, never the sentence splice wrote about it`(@TempDir tmp: Path) {
        val row = rowOf(tmp, UNAUTHORIZED, foreignKeyBody())

        assertEquals(FOREIGN_WORDS, row.providerMessage(), "the upstream's own words: $row")
        assertFalse(
            row.providerMessage().orEmpty().contains("this account did not send"),
            "splice's own clause for the client must not ride under the upstream's name: $row",
        )
    }

    /** Article VII: a secret is never repeated. The provider's sentence still reaches the row, with our key out. */
    @Test
    fun `a provider that echoes the key we sent has it masked out of the row`(@TempDir tmp: Path) {
        val said = rowOf(tmp, UNAUTHORIZED, echoedKeyBody()).providerMessage().orEmpty()

        assertFalse(SENT_TOKEN in said, "the credential splice sent never reaches a surface: $said")
        assertTrue("is not authorized" in said, "and the provider's sentence still arrives: $said")
    }

    /** A provider that quotes the request back writes its sentence ESCAPED in the body. Those are the messages an
     *  operator most wants to read, so they must not be the ones the row drops. */
    @Test
    fun `a provider sentence that quotes the request back still reaches the row`(@TempDir tmp: Path) {
        val row = rowOf(tmp, UNAUTHORIZED, QUOTING_BODY)

        assertEquals(QUOTING_WORDS, row.providerMessage(), "the sentence as the provider meant it, unescaped: $row")
    }

    @Test
    fun `the row says outright whether a capture was kept for the turn`(@TempDir tmp: Path) {
        val on = Files.createDirectories(tmp.resolve("on"))
        val off = Files.createDirectories(tmp.resolve("off"))
        val saved = rowOf(on, SERVICE_UNAVAILABLE, overloadedBody(), keepsCapture = true)
        val notSaved = rowOf(off, SERVICE_UNAVAILABLE, overloadedBody())

        assertEquals(true, saved.capture(), "a head keeping captures says so on the row: $saved")
        assertEquals(
            false,
            notSaved.capture(),
            "and one keeping none says THAT, rather than leaving a reader to infer it: $notSaved",
        )
        assertNull(notSaved["turn"], "no capture, no trace turn to open")
    }

    /** The switch is read per request, so turning capture off stops the very next one and on again saves it, with no
     *  restart of the head. */
    @Test
    fun `flipping the capture switch changes the very next request with no restart`(@TempDir tmp: Path) =
        runBlocking {
            val head = FailingHead(tmp, SERVICE_UNAVAILABLE, overloadedBody(), keepsCapture = true)
            head.start()
            try {
                val switch = checkNotNull(head.recording)
                head.turn()
                switch.set(false)
                head.turn()
                switch.set(true)
                head.turn()

                assertEquals(
                    listOf(true, false, true),
                    head.rows().map { it.capture() },
                    "saved, then not saved while off, then saved again",
                )
            } finally {
                head.close()
            }
        }
}
