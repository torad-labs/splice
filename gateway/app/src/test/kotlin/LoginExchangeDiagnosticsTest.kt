// DR-73 (invariant audit, HIGH): the CLI token-exchange failure printed `e.message` — and a
// malformed 200 from the token endpoint fails inside spec.toAuthJson's JSON parse, whose message
// quotes the response body ("JSON input:" excerpt) = live access/refresh tokens on the operator's
// terminal. The daemon-side parses of the same bodies were all guarded; only this path was not.
// The loopback endpoint serves the malformed 200 so the leak candidate is real bytes end-to-end.
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.LoginSpec
import splice.app.OAuthLoginFlow
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Path

class LoginExchangeDiagnosticsTest {

    @Test
    fun `token-exchange diagnostics never quote the response body - DR-73`(@TempDir tmp: Path) {
        val sentinel = "tok_SENTINEL_EXCHANGE"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { ex ->
            val bytes = """{"access_token":"$sentinel""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val spec = LoginSpec(
            head = "probe",
            authorizeUrl = "http://127.0.0.1/unused",
            redirectPort = 0,
            redirectPath = "/cb",
            expectedState = "s",
            tokenUrl = "http://127.0.0.1:${server.address.port}/token",
            exchangeForm = { code -> "code=$code" },
            authPath = tmp.resolve("auth.json"),
            toAuthJson = { body -> Json.parseToJsonElement(body).toString() },
        )
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        val ok = try {
            System.setOut(PrintStream(out, true))
            runBlocking { OAuthLoginFlow.exchangeAndPersist(spec, "the-code") }
        } finally {
            System.setOut(savedOut)
            server.stop(0)
        }
        val printed = out.toString()
        assertFalse(ok, "a malformed 200 is a failed login")
        assertFalse(printed.contains(sentinel), "token bytes must never reach the terminal: $printed")
        assertTrue(printed.contains("token exchange error"), printed)
    }
}
