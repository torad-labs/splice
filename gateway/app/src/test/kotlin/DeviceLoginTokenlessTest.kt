// DR-172: the device-authorization flow carried the IDENTICAL defect to OAuthLoginFlow — an HTTP
// 200 from the token endpoint was the whole test, so a body with no access token ended the poll as
// a SUCCESS over an empty credential. Both flows now go through LoginIo.persistIfSignedIn.
//
// This file is also the device flow's FIRST test. It exists because DR-172 changed that flow's
// behaviour (a tokenless 200 is now ABORT rather than SUCCESS), and a behaviour change in a login
// path with nothing exercising it is exactly the unearned claim this campaign keeps finding.
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.DeviceLoginFlow
import splice.app.DeviceLoginSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class DeviceLoginTokenlessTest {

    /** A loopback device-flow provider: a valid device authorization, then [tokenBody] on poll. */
    private fun serving(tokenBody: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/device") { ex ->
            // interval 0 and a short expiry so the poll runs once and the deadline is never the
            // thing under test; the injected waiter makes the interval a no-op regardless.
            val body = """
                {"user_code":"ABCD-EFGH","device_code":"dev-code",
                 "verification_uri":"http://127.0.0.1/verify","verification_uri_complete":"",
                 "expires_in":30,"interval":0}
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/token") { ex ->
            val body = tokenBody.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    private fun specFor(server: HttpServer, authPath: Path) = DeviceLoginSpec(
        head = "probe",
        clientId = "cid",
        deviceAuthUrl = "http://127.0.0.1:${server.address.port}/device",
        tokenUrl = "http://127.0.0.1:${server.address.port}/token",
        authPath = authPath,
        identityHeaders = emptyMap(),
        // The permissive mapping the affected providers use: an absent token becomes "".
        toAuthJson = { body ->
            val token = Regex(""""access_token"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1).orEmpty()
            """{"access_token":"$token"}"""
        },
    )

    private fun runFlow(server: HttpServer, authPath: Path): Pair<Boolean, String> {
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        return try {
            System.setOut(PrintStream(out, true))
            // A no-op waiter: the RFC 8628 interval is not what this arm is about, and without the
            // seam the arm would spend real seconds sleeping.
            runBlocking { DeviceLoginFlow.run(specFor(server, authPath), waiter = splice.spi.Waiter { }) } to
                out.toString()
        } finally {
            System.setOut(savedOut)
            server.stop(0)
        }
    }

    @Test
    fun `a device poll answering 200 with no token is not a sign-in - DR-172`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val (ok, printed) = runFlow(serving("{}"), authPath)

        assertFalse(ok, "a token endpoint that issued nothing did not sign anyone in")
        assertFalse(Files.exists(authPath), "no credential file may be created: $printed")
        assertFalse(printed.contains("credentials written"), "nothing may be reported as written: $printed")
    }

    @Test
    fun `a device poll answering 200 with a real token signs in - DR-172 control`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val (ok, printed) = runFlow(serving("""{"access_token":"tok_device"}"""), authPath)

        assertTrue(ok, printed)
        assertTrue(Files.exists(authPath), "a real token must still be persisted: $printed")
        assertTrue(Files.readString(authPath).contains("tok_device"))
    }
}
