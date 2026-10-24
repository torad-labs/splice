// NEW: LAN share, round 14 of PR #419's review. The goal: no text another machine sends reaches this machine's terminal,
// its log or its Sharing page with a character a terminal acts on or cannot show. A machine's description is read before
// any pin exists, from whatever answers at the address, and `splice remote` prints it and asks whether to trust it; a
// refused join and a joined machine's refusal are printed, logged and shown as the reason it does not answer. Each
// arrives with control, format and unprintable characters left out (C0, C1, DEL, and the Unicode Cc, Cf, Zl and Zp
// classes, as `splice sessions` prints Claude Code's registry), and a refusal is cut to a line's length. A machine of
// this test's own answers over TLS with descriptions and refusals drawn from escape sequences, bidi overrides and plain
// text, since no list of examples covers what a hostile answer can hold.
package splice.share.remote

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.share.SHARE_INFO_PATH
import splice.share.ShareCertificate
import splice.share.ShareInfo
import splice.share.SharedHead
import splice.share.SharedModel
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.random.Random

// why: a fixed seed, so a failing draw is the same draw next time.
private const val TEXT_SEED = 14

// why: enough answers that every piece of the alphabet lands in every field several times.
private const val TEXT_RUNS = 40

// why: a refusal longer than any line, so one that is not cut shows.
private const val LONG_TEXT = 3_000

// why: the most of a refusal's own words a message carries: a line's length, as the rest of the log keeps one.
private const val SAID_AT_MOST = 200

// why: what a terminal acts on or cannot show, by Unicode class, as `splice sessions` reads Claude Code's text.
private val UNPRINTABLE = setOf(
    Character.CONTROL,
    Character.FORMAT,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    Character.UNASSIGNED,
    Character.PRIVATE_USE,
    Character.SURROGATE,
).map { it.toInt() }.toSet()

// why: pieces a hostile answer is made of: a screen clear, a cursor move, a clipboard write, a bell, an 8-bit escape,
// a right-to-left override, a zero-width space, a line break, a private character, and plain text in several scripts.
private val PIECES = listOf(
    "\u001b[2J", "\u001b[1A", "\u001b]52;c;aGk=\u0007", "\u0007", "\u009b31m", "‮", "​", "\n", "\r",
    " ", "", "\u007f", "torad", "-server", " ", "é", "漢", "🙂", "1.0", ":", "/",
)

class RemoteTextTest {
    @TempDir
    lateinit var tmp: Path

    private val json = Json { encodeDefaults = true }

    /** A machine on the network that answers whatever this test sets: [info] at /share/v1/info, and [refusal], with
     *  [status], to anything else. Served over TLS with a certificate of its own, as a sharing machine is. */
    private inner class Answering : AutoCloseable {
        private val tls = ShareCertificate(tmp.resolve("answering.pem")).load(listOf("localhost"))
        val fingerprint: String = tls.fingerprint

        @Volatile var info = ""

        @Volatile var refusal = ""

        @Volatile var status = HTTP_REFUSED

        private val server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(tls.keyStore, tls.password)
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/") { exchange ->
                val described = exchange.requestURI.path == SHARE_INFO_PATH
                val (code, body) = if (described) HTTP_OK to info else status to refusal
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val port: Int get() = server.address.port

        override fun close() = server.stop(0)
    }

    private fun drawn(random: Random, longest: Int = 12): String =
        (0 until random.nextInt(1, longest)).joinToString("") { PIECES[random.nextInt(PIECES.size)] }

    private fun drawnInfo(random: Random): ShareInfo {
        val model = SharedModel(drawn(random), drawn(random), 200_000, listOf(drawn(random)))
        val head = SharedHead(drawn(random), drawn(random), listOf(model), drawn(random))
        return ShareInfo(drawn(random), drawn(random), listOf(head), drawn(random))
    }

    /** Every text of [info]. */
    private fun texts(info: ShareInfo): List<String> = listOfNotNull(info.server, info.version, info.fingerprint) +
        info.heads.flatMap { head ->
            listOfNotNull(head.key, head.command, head.family) +
                head.models.flatMap { listOf(it.id, it.label) + it.slots }
        }

    /** [text] as the goal reads it: with every unprintable character left out, and nothing else. */
    private fun printable(text: String): String = buildString {
        text.codePoints().filter { Character.getType(it) !in UNPRINTABLE }.forEach { appendCodePoint(it) }
    }

    @Test
    fun `whatever a machine's description holds, it is read with every unprintable character left out`() {
        Answering().use { machine ->
            val http = RemoteHttp(RemoteTrust(null, "a machine on the network"))
            val random = Random(TEXT_SEED)
            repeat(TEXT_RUNS) { run ->
                val sent = drawnInfo(random)
                machine.info = json.encodeToString(ShareInfo.serializer(), sent)

                val read = checkNotNull(http.info("127.0.0.1", machine.port).get()) { "run $run: it was read" }

                assertEquals(texts(sent).map(::printable), texts(read), "run $run")
            }
        }
    }

    @Test
    fun `whatever a machine refuses a join or a read with, what is said of it is printable and a line long at most`() {
        Answering().use { machine ->
            val store = RemotesStore(tmp.resolve("remotes.json")).also { it.save("answering", "a token") }
            val api = RemoteApi("answering", RemoteTrust(machine.fingerprint, "answering"), store)
            val http = RemoteHttp(RemoteTrust(machine.fingerprint, "answering"))
            val random = Random(TEXT_SEED)
            repeat(TEXT_RUNS) { run ->
                val words = drawn(random, LONG_TEXT)
                machine.refusal = buildJsonObject { putJsonObject("error") { put("message", words) } }.toString()

                val read = runCatching { api.sessions("https://127.0.0.1:${machine.port}") }.exceptionOrNull()
                val said = (read as RemoteUnanswered).said
                val joined = runCatching { http.join("127.0.0.1", machine.port, "mini") }.exceptionOrNull()
                val refused = checkNotNull(joined?.message) { "run $run: the join was refused" }

                for (text in listOf(said, refused)) {
                    assertEquals(printable(text), text, "run $run: nothing unprintable is said")
                    val own = printable(words).let { it.take(minOf(it.length, SAID_AT_MOST)) }
                    assertTrue(own.take(SAID_AT_MOST / 2) in text || own.isEmpty(), "run $run: its words are said")
                    assertTrue(text.length <= SAID_AT_MOST * 2, "run $run: a line long at most (${text.length})")
                }
            }
        }
    }
}

// why: the statuses the machine of this test answers with.
private const val HTTP_OK = 200
private const val HTTP_REFUSED = 500
