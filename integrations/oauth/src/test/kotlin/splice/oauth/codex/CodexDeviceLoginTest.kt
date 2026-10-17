// Spec section 11: the console signs a ChatGPT account in through OpenAI's device-code sequence, against a local
// stand-in for auth.openai.com. The code and link reach the observer, never a terminal or a browser; the code
// exchange uses the verifier the SERVER minted and the device callback redirect; the credential lands on disk.
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
import splice.oauth.LoginAnnouncement
import splice.oauth.LoginObserver
import splice.upstream.Waiter
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val GRANT =
    """{"authorization_code":"auth-code","code_challenge":"c","code_verifier":"server-verifier"}"""

class CodexDeviceLoginTest {

    private class FakeIssuer {
        val polls = AtomicInteger()
        val exchangeBody = AtomicReference<String>()
        val usercodeBody = AtomicReference<String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val issuer get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/api/accounts/deviceauth/usercode") { ex ->
                usercodeBody.set(String(ex.requestBody.readAllBytes()))
                reply(ex, 200, """{"device_auth_id":"dev-1","user_code":"ABCD-1234","interval":"0"}""")
            }
            server.createContext("/api/accounts/deviceauth/token") { ex ->
                ex.requestBody.readAllBytes()
                if (polls.incrementAndGet() < 3) {
                    reply(ex, 404, "")
                } else {
                    reply(ex, 200, GRANT)
                }
            }
            server.createContext("/oauth/token") { ex ->
                exchangeBody.set(String(ex.requestBody.readAllBytes()))
                reply(ex, 200, """{"access_token":"tok-device","refresh_token":"r","id_token":"i"}""")
            }
            server.start()
        }

        private fun reply(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
    }

    @Test
    fun `an observed ChatGPT device login announces the code, waits out the pending polls, and writes the credential`(
        @TempDir tmp: Path,
    ) {
        val fake = FakeIssuer()
        val authPath = tmp.resolve("auth.json")
        val announced = AtomicReference<LoginAnnouncement?>()
        val out = StringBuilder()
        val spec = CodexDeviceSpec(
            head = "codex",
            issuer = fake.issuer,
            clientId = "cid",
            tokenUrl = "${fake.issuer}/oauth/token",
            authPath = authPath,
            account = null,
            toAuthJson = { body ->
                val token = Regex(""""access_token"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1).orEmpty()
                """{"tokens":{"access_token":"$token"}}"""
            },
        )

        val ok = try {
            runBlocking {
                CodexDeviceLogin(TerminalOutput { out.appendLine(it) })
                    .run(spec, Waiter {}, LoginObserver { announced.set(it) })
            }
        } finally {
            fake.server.stop(0)
        }

        assertTrue(ok, out.toString())
        assertEquals("ABCD-1234", announced.get()?.userCode)
        assertEquals("${fake.issuer}/codex/device", announced.get()?.verificationUri)
        assertFalse(out.toString().contains("enter this code"), "an observed login prints nothing to a terminal: $out")
        assertEquals(3, fake.polls.get(), "two pending answers, then the grant")
        assertTrue(fake.usercodeBody.get().contains("\"client_id\":\"cid\""), fake.usercodeBody.get())
        val exchange = fake.exchangeBody.get()
        assertTrue(exchange.contains("code_verifier=server-verifier"), exchange)
        assertTrue(exchange.contains("code=auth-code"), exchange)
        assertTrue(exchange.contains("deviceauth%2Fcallback"), exchange)
        assertTrue(Files.readString(authPath).contains("tok-device"))
    }

    @Test
    fun `a server that has not enabled device login says so and writes nothing`(@TempDir tmp: Path) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
        val authPath = tmp.resolve("auth.json")
        val out = StringBuilder()
        val spec = CodexDeviceSpec(
            head = "codex",
            issuer = "http://127.0.0.1:${server.address.port}",
            clientId = "cid",
            tokenUrl = "http://127.0.0.1:${server.address.port}/oauth/token",
            authPath = authPath,
            account = null,
            toAuthJson = { "{}" },
        )

        val ok = try {
            runBlocking { CodexDeviceLogin(TerminalOutput { out.appendLine(it) }).run(spec, Waiter {}, null) }
        } finally {
            server.stop(0)
        }

        assertFalse(ok)
        assertTrue(out.toString().contains("not enabled"), out.toString())
        assertFalse(Files.exists(authPath))
    }
}
