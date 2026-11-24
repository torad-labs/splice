// What server mode's Messages route hands a command's head of the headers its provider's `forward_headers` names
// (docs/specs/server-mode.md, F1), over real HTTP on loopback. The head sends those headers on to the provider, so a
// header it is handed leaves the machine. A person's key is the server's secret: a named header rides as it was sent,
// unless its value holds a key this server issued, and then the head is not handed it and the turn is still answered.
// Each test reads what the head got.
package splice.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList

internal class ServerMessagesHeadersTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val logged = CopyOnWriteArrayList<String>()

    /** The headers each turn reached the head with, by lowercased name. */
    private val handed = CopyOnWriteArrayList<Map<String, String>>()

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { exchange -> exchange.use(::answer) }
        start()
    }

    /** `claude-team`'s provider names two headers in its `forward_headers`. */
    private val upstreams = object : HeadUpstreams {
        override fun forwarded(head: String): List<String> = listOf(USER, NOTE).takeIf { head == "team" }.orEmpty()
    }
    private val rig = ServerRig(LogSink { logged += it }, upstreams = upstreams)

    /** The head answers a turn the way it was asked: an event stream, or one JSON body. */
    private fun answer(exchange: HttpExchange) {
        val streamed = "\"stream\":true" in exchange.requestBody.use { String(it.readBytes()) }
        handed += exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }
        val body = (if (streamed) STREAMED_ANSWER else WHOLE_ANSWER).toByteArray()
        exchange.responseHeaders.add("Content-Type", if (streamed) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body)
    }

    @AfterEach
    fun stop() {
        rig.close()
        head.stop(0)
    }

    /** Server mode over `claude-team`, and a key for each of two people. */
    private fun served(): Pair<String, String> {
        rig.open(state, listOf(StandInHead(head.address.port)))
        return rig.keyFor("ola@example.com") to rig.keyFor("ben@example.com")
    }

    /** A turn on the Messages route with [key], carrying [own] as headers of the caller's own. */
    private fun turn(key: String, stream: Boolean, vararg own: Pair<String, String>): HttpResponse<String> {
        val body = """{"model":"m","stream":$stream,"messages":[]}"""
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/claude-team/v1/messages"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
        own.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The headers of the one turn the head got since [from], none of which may hold any of [keys]. */
    private fun handedOn(from: Int, vararg keys: String): Map<String, String> {
        val headers = handed.drop(from).single()
        val carriers = headers.filterValues { value -> keys.any { it in value } }.keys
        assertTrue(carriers.isEmpty(), "the head was handed a person's key in $carriers")
        return headers
    }

    @Test
    fun `a named header with an ordinary value is handed to the head as it was sent`() {
        val (ola, ben) = served()
        assertEquals(200, turn(ola, stream = false, USER to "ola", NOTE to "the platform team").statusCode())
        val headers = handedOn(from = 0, ola, ben)
        assertEquals("ola", headers[USER])
        assertEquals("the platform team", headers[NOTE])
        assertTrue(logged.none { "did not forward" in it }, "nothing named was held back: $logged")
    }

    @Test
    fun `a named header that holds the caller's own key stays here, and the turn is answered`() {
        val (ola, ben) = served()
        val answer = turn(ola, stream = false, USER to ola, NOTE to "for $ola today")
        assertEquals(200, answer.statusCode())
        assertEquals(WHOLE_ANSWER, answer.body())
        val headers = handedOn(from = 0, ola, ben)
        assertNull(headers[USER])
        assertNull(headers[NOTE])
    }

    @Test
    fun `one that holds another person's key stays here, streamed or not, and the one beside it rides`() {
        val (ola, ben) = served()
        for ((at, stream) in listOf(true, false).withIndex()) {
            val answer = turn(ola, stream, USER to "ola", NOTE to "token=$ben;")
            assertEquals(200, answer.statusCode())
            assertEquals(if (stream) STREAMED_ANSWER else WHOLE_ANSWER, answer.body())
            val headers = handedOn(from = at, ola, ben)
            assertNull(headers[NOTE])
            assertEquals("ola", headers[USER])
        }
        val lines = logged.filter { "did not forward" in it }
        assertEquals(2, lines.size, "one line for each turn: $logged")
        assertTrue(lines.all { NOTE in it && USER !in it }, "the log names the header held back: $lines")
        assertFalse(logged.any { ola in it || ben in it }, "the log never repeats a key")
    }

    @Test
    fun `benign text at a registered prefix rides while imported 32-char, 64-char and mismatched keys stay here`() {
        val mode = rig.open(state, listOf(StandInHead(head.address.port)))
        val ola = rig.keyFor("ola@example.com")
        val olaPrefix = ola.substringBeforeLast('_')
        val key32 = "spk_short032_" + "d".repeat(32)
        val key64 = "team_longer64_" + "b".repeat(64)
        val mismatchedActual = "legacy_actual99_" + "c".repeat(48)
        val imports = listOf("spk_short032" to key32, "team_longer64" to key64, "spk_stored01" to mismatchedActual)
        for ((prefix, secret) in imports) {
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
            val body = buildJsonObject {
                put("person", "ada@example.com")
                put("prefix", prefix)
                put("hash", digest)
            }
            assertInstanceOf(ServerAnswer.Done::class.java, runBlocking { mode.keyApi.importKey(body, "test") })
        }
        val benign = "${olaPrefix}_${"z".repeat(40)}"
        val cases = listOf("k=${key32}extraSuffix", "k=${key64}extraSuffix", "t=${mismatchedActual}extraSuffix")
        cases.forEachIndexed { index, leaked ->
            assertEquals(200, turn(ola, stream = false, USER to benign, NOTE to leaked).statusCode())
            val seen = handedOn(from = index, ola, key32, key64, mismatchedActual)
            assertEquals(benign, seen[USER])
            assertNull(seen[NOTE])
        }
    }
}

private const val USER = "x-team-user"
private const val NOTE = "x-team-note"

private const val WHOLE_ANSWER = """{"type":"message","content":[{"type":"text","text":"from the head"}]}"""
private const val STREAMED_ANSWER = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
