// NEW: V4-251 — the paste reader of a browser sign-in reads the terminal only while the sign-in
// waits. It used to park a thread in readlnOrNull that outlived the sign-in, so once the browser won,
// the next line the user typed went to that thread and was thrown away: in take2-plans-1 the
// live-turn prompt after `splice add codex` needed its "n" typed twice. Each arm installs a fake
// terminal as System.in, types after the sign-in ends, and reads the next line with the prompter's
// own call (ConsolePrompter, AddWiring.kt: readlnOrNull), so the red is the real contention: Kotlin's
// LineReader serializes both readers, and the parked one always wins the line.
package splice.oauth

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

private const val FLOW_TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

/** How long the next prompt waits for its line: generous, because the fixed flow leaves it at once. */
private const val NEXT_LINE_MS = 2_000L

private const val EOF = -1

/** A terminal in canonical mode: a typed line becomes readable whole, and a read blocks until one does. */
private class FakeTerminal : InputStream() {
    private val bytes = LinkedBlockingQueue<Int>()

    fun type(vararg lines: String) {
        lines.forEach { line -> "$line\n".toByteArray().forEach { bytes.put(it.toInt() and 0xff) } }
    }

    /** End of input, which releases any reader still parked here once a test is over. */
    override fun close() {
        bytes.put(EOF)
    }

    override fun read(): Int {
        val b = bytes.take()
        if (b == EOF) bytes.put(EOF)
        return b
    }

    override fun available(): Int = bytes.count { it != EOF }
}

/** A person at the terminal the test installed as System.in. */
private class Terminal(private val interactive: Boolean = true) : PasteSource {
    override fun interactive(): Boolean = interactive

    override fun input(): InputStream = System.`in`
}

private class Announced : LoginObserver {
    val detail = AtomicReference<LoginAnnouncement?>(null)

    override fun announced(detail: LoginAnnouncement) {
        this.detail.set(detail)
    }
}

class OAuthPasteReaderTest {

    private val lines = CopyOnWriteArrayList<String>()
    private val exchanged = CopyOnWriteArrayList<String>()
    private val redirectPort = TestPorts.reserve()
    private val base = "http://127.0.0.1:$redirectPort/cb"
    private val terminal = FakeTerminal()
    private val savedIn: InputStream = System.`in`
    private val prompter = Executors.newSingleThreadExecutor()

    @BeforeEach
    fun installTerminal() {
        System.setIn(terminal)
    }

    @AfterEach
    fun restoreTerminal() {
        terminal.close()
        prompter.shutdown()
        System.setIn(savedIn)
    }

    /** The next prompt's read, the prompter's own call, or null when no line reaches it in time. */
    private fun nextPromptLine(): String? = try {
        prompter.submit<String?> { readlnOrNull() }.get(NEXT_LINE_MS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    }

    private fun servingToken(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/token") { ex ->
            exchanged += ex.requestBody.readBytes().decodeToString()
            val bytes = """{"access_token":"tok_paste_probe"}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun spec(tmp: Path, token: HttpServer) = LoginSpec(
        head = "probe",
        authorizeUrl = "http://127.0.0.1/unused?state=current",
        redirectPort = redirectPort,
        redirectPath = "/cb",
        expectedState = "current",
        tokenUrl = "http://127.0.0.1:${token.address.port}/token",
        exchangeForm = { code -> "code=$code" },
        authPath = tmp.resolve("auth.json"),
        toAuthJson = { body -> body },
    )

    /** Runs one sign-in; [whileWaiting] acts once the flow is waiting for its callback. */
    private fun signIn(tmp: Path, source: PasteSource, whileWaiting: suspend (HttpClient) -> Unit): Boolean {
        val token = servingToken()
        val client = HttpClient(CIO)
        val observer = Announced()
        val flow = OAuthLoginFlow(TerminalOutput { lines += it }, paste = source)
        return try {
            runBlocking {
                val running = async(Dispatchers.IO) { flow.run(spec(tmp, token), observer) }
                withTimeout(FLOW_TIMEOUT_MS) {
                    while (observer.detail.get() == null) delay(POLL_MS)
                }
                whileWaiting(client)
                withTimeout(FLOW_TIMEOUT_MS) { running.await() }
            }
        } finally {
            client.close()
            token.stop(0)
        }
    }

    @Test
    fun `once the browser sign-in wins, the next line typed goes to the next prompt`(@TempDir tmp: Path) {
        val ok = signIn(tmp, Terminal()) { client -> client.get("$base?code=the-code&state=current") }
        assertTrue(ok, "the loopback callback signs in: $lines")

        terminal.type("n")
        assertEquals("n", nextPromptLine(), "the live-turn prompt's answer, typed once")
    }

    @Test
    fun `two prompts in a row after a sign-in each get their own line`(@TempDir tmp: Path) {
        assertTrue(signIn(tmp, Terminal()) { client -> client.get("$base?code=the-code&state=current") })

        terminal.type("n", "y")
        assertEquals("n", nextPromptLine())
        assertEquals("y", nextPromptLine())
    }

    @Test
    fun `a pasted code still signs in, and a line typed after it is left for the next prompt`(@TempDir tmp: Path) {
        val ok = signIn(tmp, Terminal()) {
            // Both lines arrive together: the paste reader takes the code and must leave the answer.
            terminal.type("http://localhost:$redirectPort/cb?code=pasted-code&state=current", "n")
        }
        assertTrue(ok, "the pasted code goes through the same exchange: $lines")
        assertEquals(listOf("code=pasted-code"), exchanged)
        assertEquals("n", nextPromptLine())
    }

    @Test
    fun `a sign-in that fails leaves the next line to the next prompt`(@TempDir tmp: Path) {
        val ok = signIn(tmp, Terminal()) { client -> client.get("$base?error=access_denied&state=current") }
        assertFalse(ok)

        terminal.type("n")
        assertEquals("n", nextPromptLine())
    }

    /** No terminal (a detached or piped run, the console's off-request sign-in): no prompt, no read. */
    @Test
    fun `without a terminal the sign-in prints no paste prompt and reads nothing`(@TempDir tmp: Path) {
        terminal.type("typed before the sign-in")
        val ok = signIn(tmp, Terminal(interactive = false)) { client -> client.get("$base?code=c&state=current") }
        assertTrue(ok)

        assertFalse(lines.any { "paste the redirect URL" in it }, lines.toString())
        assertEquals("typed before the sign-in", nextPromptLine())
    }
}
