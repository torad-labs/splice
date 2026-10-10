// A chat stream that opens a tool call and is torn before the client saw anything must be re-issued whole.
//
// Driven the way a client drives it: a real HeadServer on the openai-chat dialect and a raw upstream socket that sends
// a tool-call id with an opening-brace argument fragment and no function name, then resets the connection.
// Every assertion is on the bytes the client receives and on the requests the upstream saw. The first attempt is a
// tear before any client-visible content, so the head owes the client a clean second attempt: a half-built tool call
// forwarded on the way out would count as content and block that reissue.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.dialect.chat.ChatQuirks
import splice.head.HeadServer
import splice.head.headDeps
import splice.head.headStores
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val INFERENCE_TOKEN = "test-inference-token"
private const val ANSWER = "forty-two."
private const val DELIVERY_PAUSE_MS = 300L
private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")

// The shape the review found: an id and an opening brace, no function name, then the connection is reset.
private const val TORN_TOOL_CALL =
    """data: {"id":"c1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",""" +
        """"function":{"arguments":"{"}}]}}]}""" + "\n\n"

private const val ANSWER_FRAMES =
    """data: {"id":"c2","choices":[{"index":0,"delta":{"content":"$ANSWER"}}]}""" + "\n\n" +
        """data: {"id":"c2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n" +
        "data: [DONE]\n\n"

/** Attempt one tears after the tool-call fragment; every later attempt answers. */
private class TearingChatUpstream {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"
    val requestBodies = CopyOnWriteArrayList<String>()

    fun start() {
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
                val _ = runCatching { socket.use { serve(it) } }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        val _ = runCatching { server.close() }
    }

    private fun serve(socket: Socket) {
        val body = readRequest(socket.getInputStream())
        val torn = requestBodies.isEmpty()
        requestBodies.add(body)
        val out = socket.getOutputStream()
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        if (torn) {
            out.chunk(TORN_TOOL_CALL)
            // The reset: close() with a zero linger sends RST instead of FIN. The client must have READ the fragment
            // before the reset lands, or the transport reports a clean end of body instead of a torn one, so the
            // reset waits one delivery pause. Only a raw socket can express this tear, and nothing of the client's is
            // visible from here to wait on instead.
            // ast-grep-ignore: kt-tests-no-wall-clock -- the pause is the delivery the tear is measured against
            Thread.sleep(DELIVERY_PAUSE_MS)
            socket.setSoLinger(true, 0)
        } else {
            out.chunk(ANSWER_FRAMES)
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun readRequest(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val c = input.read()
            if (c < 0) return ""
            head.append(c.toChar())
        }
        val length = CONTENT_LENGTH.find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val bytes = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(bytes, off, length - off)
            if (n < 0) break
            off += n
        }
        return String(bytes, Charsets.UTF_8)
    }
}

private fun OutputStream.chunk(payload: String) {
    val bytes = payload.toByteArray()
    write("${bytes.size.toString(16)}\r\n".toByteArray())
    write(bytes)
    write("\r\n".toByteArray())
    flush()
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatTornToolCallTest {
    private val upstream = TearingChatUpstream()
    private lateinit var head: HeadServer
    private val journal = CopyOnWriteArrayList<String>()

    @BeforeAll
    fun setUp(@TempDir tmp: Path) = runBlocking {
        upstream.start()
        val provider = OpenAiChatProvider(
            ProviderTuning(
                name = ProviderName(key = "tearchat", label = "TearChat"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-tearchat--",
                    models = listOf(ModelEntry("chat-model", "Chat", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "chat-model",
                auth = ApiKeyAuthProvider("TEST_TEARCHAT_KEY", envReader = { "key" }),
                locations = ProviderLocations(baseUrl = upstream.baseUrl),
                watchdog = WatchdogBudget(120.seconds, 120.seconds, 300.seconds),
            ),
            ChatQuirks(providerTag = "tearchat"),
        )
        val deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 60_000, maxRetries = 4),
            gate = InflightGate({ 4 }),
            log = { line -> journal.add(line) },
        )
        head = HeadServer(
            provider = provider,
            listenPort = 0,
            deps = deps.copy(
                tokens = deps.tokens.copy(inferenceToken = INFERENCE_TOKEN),
                stores = headStores(tmp),
            ),
        )
        head.start()
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        upstream.stop()
    }

    @Test
    fun `a tear after a half-built tool call and before any content is re-issued and the client sees no tool call`() {
        val received = drainTurn() + "\n-- head log --\n" + journal.joinToString("")

        assertTrue(received.contains(ANSWER), "the re-issued attempt answers the turn: $received")
        assertFalse(received.contains("tool_use"), "no half-built tool call reaches the client: $received")
        assertFalse(received.contains("event: error"), "the client sees no error: $received")
        assertEquals(1, received.split("event: message_stop").size - 1, "one terminal for the turn: $received")
        assertEquals(2, upstream.requestBodies.size, "the request was re-issued once")
    }

    private fun drainTurn(): String {
        val body = """{"model":"claude-tearchat--chat-model","stream":true,"max_tokens":64,""" +
            """"messages":[{"role":"user","content":"what is the answer"}]}"""
        return Socket("127.0.0.1", head.port).use { socket ->
            socket.soTimeout = 60_000
            val request = "POST /v1/messages HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${head.port}\r\n" +
                "Authorization: Bearer $INFERENCE_TOKEN\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" + body
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val buffer = ByteArray(8 * 1024)
            val seen = StringBuilder()
            while (true) {
                val n = runCatching { socket.getInputStream().read(buffer) }.getOrDefault(-1)
                if (n < 0) break
                seen.append(String(buffer, 0, n, Charsets.UTF_8))
            }
            seen.toString()
        }
    }
}
