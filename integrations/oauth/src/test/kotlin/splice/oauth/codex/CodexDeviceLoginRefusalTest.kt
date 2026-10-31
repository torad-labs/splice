// Spec section 11, review findings 3 and 7: a refused ChatGPT device login logs the HTTP status and the OAuth error
// code and never the response body, and a server poll interval longer than the code's life never sleeps past it.
package splice.oauth.codex

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.oauth.LoginObserver
import splice.upstream.Waiter
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val SECRET_BODY =
    """{"error":"invalid_grant","refresh_token":"rt-SECRET-123","email":"someone@private.example"}"""

class CodexDeviceLoginRefusalTest {

    private fun issuer(
        intervalS: String,
        polls: AtomicInteger,
        exchangeStatus: Int,
        grantAnswer: String? = null,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun reply(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.createContext("/api/accounts/deviceauth/usercode") { ex ->
            ex.requestBody.readAllBytes()
            reply(ex, 200, """{"device_auth_id":"d","user_code":"AB-12","interval":"$intervalS"}""")
        }
        server.createContext("/api/accounts/deviceauth/token") { ex ->
            ex.requestBody.readAllBytes()
            polls.incrementAndGet()
            reply(ex, if (grantAnswer == null) 404 else 200, grantAnswer.orEmpty())
        }
        server.createContext("/oauth/token") { ex ->
            ex.requestBody.readAllBytes()
            reply(ex, exchangeStatus, SECRET_BODY)
        }
        server.start()
        return server
    }

    private fun spec(server: HttpServer, authPath: Path) = CodexDeviceSpec(
        head = "codex",
        issuer = "http://127.0.0.1:${server.address.port}",
        clientId = "cid",
        tokenUrl = "http://127.0.0.1:${server.address.port}/oauth/token",
        authPath = authPath,
        account = null,
        toAuthJson = { "{}" },
    )

    @Test
    fun `a refused token exchange logs the status and the error code, never the body`(@TempDir tmp: Path) {
        val server = issuer(
            "0",
            AtomicInteger(),
            exchangeStatus = 400,
            grantAnswer = """{"authorization_code":"c","code_challenge":"x","code_verifier":"v"}""",
        )
        val out = StringBuilder()

        val ok = try {
            runBlocking {
                CodexDeviceLogin(TerminalOutput { out.appendLine(it) })
                    .run(spec(server, tmp.resolve("auth.json")), Waiter {}, LoginObserver { })
            }
        } finally {
            server.stop(0)
        }

        assertFalse(ok)
        assertTrue(out.contains("HTTP 400") && out.contains("invalid_grant"), out.toString())
        assertFalse(out.contains("rt-SECRET-123") || out.contains("someone@private.example"), out.toString())
        assertFalse(Files.exists(tmp.resolve("auth.json")))
    }

    @Test
    fun `an interval longer than the time left sleeps only the time left and never polls past expiry`(
        @TempDir tmp: Path,
    ) {
        val polls = AtomicInteger()
        val server = issuer("3600", polls, exchangeStatus = 200)
        val waits = CopyOnWriteArrayList<Long>()
        val out = StringBuilder()

        val ok = try {
            runBlocking {
                CodexDeviceLogin(TerminalOutput { out.appendLine(it) }, maxWaitMs = 300)
                    .run(
                        spec(server, tmp.resolve("auth.json")),
                        Waiter { ms ->
                            waits += ms
                            val until = System.currentTimeMillis() + ms
                            while (System.currentTimeMillis() < until) Thread.onSpinWait()
                        },
                        LoginObserver { },
                    )
            }
        } finally {
            server.stop(0)
        }

        assertFalse(ok)
        assertEquals(1, waits.size, "one capped sleep: $waits")
        assertTrue(waits.single() <= 300, "the sleep is capped at the time left: $waits")
        assertEquals(0, polls.get(), "no poll after the code's life ended")
        assertTrue(out.contains("expired"), out.toString())
    }
}
