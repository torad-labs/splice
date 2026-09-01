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
import splice.spi.Waiter
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class DeviceLoginTokenlessTest {

    /** A loopback device-flow provider: a valid device authorization, then [tokenBody] on poll. */
    private fun serving(tokenBody: String, expiresIn: Long = 30, interval: Long = 0): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/device") { ex ->
            // interval 0 and a short expiry so the poll runs once and the deadline is never the
            // thing under test; the injected waiter makes the interval a no-op regardless.
            val body = """
                {"user_code":"ABCD-EFGH","device_code":"dev-code",
                 "verification_uri":"http://127.0.0.1/verify","verification_uri_complete":"",
                 "expires_in":$expiresIn,"interval":$interval}
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

    private fun runFlow(server: HttpServer, authPath: Path, waiter: Waiter = Waiter { }): Pair<Boolean, String> {
        val savedOut = System.out
        val out = ByteArrayOutputStream()
        return try {
            System.setOut(PrintStream(out, true))
            // A no-op waiter: the RFC 8628 interval is not what this arm is about, and without the
            // seam the arm would spend real seconds sleeping.
            runBlocking { DeviceLoginFlow.run(specFor(server, authPath), waiter = waiter) } to out.toString()
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

    // DR-190 (DR-177's unenumerated fifth site): expires_in and interval come off the wire. A value that
    // does not fit in milliseconds wrapped `now + expiresInS * 1000` negative, so the poll never ran and
    // every attempt was EXPIRED before the first token request; the same product on the interval made
    // the wait negative. The deadline now degrades the way DR-177's credential expiry does.
    @Test
    fun `an expires_in past the millisecond range still polls and signs in - DR-190`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val (ok, printed) = runFlow(serving("""{"access_token":"tok_device"}""", expiresIn = Long.MAX_VALUE), authPath)

        assertTrue(ok, "an unrepresentable lifetime is not an instant expiry: $printed")
        assertTrue(Files.exists(authPath), printed)
    }

    @Test
    fun `an interval past the millisecond range waits a bounded, non-negative time - DR-190`(@TempDir tmp: Path) {
        val authPath = tmp.resolve("auth.json")
        val waits = mutableListOf<Long>()
        val (ok, printed) = runFlow(
            serving("""{"access_token":"tok_device"}""", interval = Long.MAX_VALUE),
            authPath,
            waiter = Waiter { waits += it },
        )

        assertTrue(ok, printed)
        assertTrue(waits.isNotEmpty(), "the poll must have waited at least once")
        assertTrue(waits.all { it in 0L..MAX_POLL_WAIT_MS }, "every wait must be bounded and non-negative: $waits")
    }
}

// One hour, the cap DeviceLoginFlow applies to a wire interval before multiplying it into milliseconds.
private const val MAX_POLL_WAIT_MS = 3_600_000L
