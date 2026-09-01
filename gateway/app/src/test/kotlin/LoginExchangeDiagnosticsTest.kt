// DR-73 (invariant audit, HIGH): the CLI token-exchange failure printed `e.message` — and a
// malformed 200 from the token endpoint fails inside spec.toAuthJson's JSON parse, whose message
// quotes the response body ("JSON input:" excerpt) = live access/refresh tokens on the operator's
// terminal. The daemon-side parses of the same bodies were all guarded; only this path was not.
// The loopback endpoint serves the malformed 200 so the leak candidate is real bytes end-to-end.
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.LoginSpec
import splice.app.OAuthLoginFlow
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
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

// DR-172 (grok-splice source sweep): the exchange boundary treated an HTTP 200 as the whole test.
// Codex and Grok both map access_token through orEmpty(), so a token endpoint answering 200 with
// {} had an EMPTY access token written at 0600 under "signed in — credentials written to …" and
// the operator walked away believing they were authenticated. Kimi already refused the same input
// with a hard error, so the correct behaviour was established in-repo and two providers diverged.
class LoginTokenlessSuccessTest {

    /** A loopback token endpoint answering 200 with [body]. */
    private fun serving(body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { ex ->
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    /** The EXACT mapping LoginCodex and LoginGrok use — str(...) ?: "" — which is what turned a
     *  tokenless 200 into an empty credential instead of a refusal. */
    private fun specFor(server: HttpServer, authPath: Path) = LoginSpec(
        head = "probe",
        authorizeUrl = "http://127.0.0.1/unused",
        redirectPort = 0,
        redirectPath = "/cb",
        expectedState = "s",
        tokenUrl = "http://127.0.0.1:${server.address.port}/token",
        exchangeForm = { code -> "code=$code" },
        authPath = authPath,
        toAuthJson = { body ->
            val token = (Json.parseToJsonElement(body).jsonObject["access_token"] as? JsonPrimitive)
                ?.content.orEmpty()
            """{"access_token":"$token"}"""
        },
    )

    private fun exchange(server: HttpServer, authPath: Path): Pair<Boolean, String> {
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        return try {
            System.setOut(PrintStream(out, true))
            runBlocking { OAuthLoginFlow.exchangeAndPersist(specFor(server, authPath), "the-code") } to
                out.toString()
        } finally {
            System.setOut(savedOut)
            server.stop(0)
        }
    }

    @Test
    fun `a 200 carrying no access token is not a sign-in and writes nothing - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val (ok, printed) = exchange(serving("{}"), authPath)

        assertFalse(ok, "a token endpoint that issued nothing did not sign anyone in")
        assertFalse(Files.exists(authPath), "no credential file may be created: $printed")
        // Matched on the SUCCESS sentence, not on the words "signed in": the refusal legitimately
        // says "NOT signed in", and a substring test that cannot tell those apart would fail on
        // correct output and pass on a reworded leak.
        assertFalse(printed.contains("credentials written"), "nothing may be reported as written: $printed")
        assertTrue(printed.contains("no access token"), printed)
    }

    @Test
    fun `a tokenless 200 leaves an existing credential untouched - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        Files.writeString(authPath, """{"access_token":"still-valid"}""")

        val (ok, printed) = exchange(serving("{}"), authPath)

        assertFalse(ok, printed)
        // Refusing is not enough: replacing a WORKING credential with a worthless one would be a
        // worse outcome than the original defect, since the operator loses a session that worked.
        assertEquals(
            """{"access_token":"still-valid"}""",
            Files.readString(authPath),
            "a refused exchange must not overwrite the credential already on disk",
        )
    }

    @Test
    fun `a 200 carrying a real token still signs in - DR-172 control`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val (ok, printed) = exchange(serving("""{"access_token":"tok_real"}"""), authPath)

        assertTrue(ok, printed)
        assertTrue(Files.exists(authPath), "a real token must still be persisted: $printed")
        assertTrue(printed.contains("signed in"), printed)
        assertTrue(Files.readString(authPath).contains("tok_real"))
    }
}
