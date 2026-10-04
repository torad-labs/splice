// NEW: V4-132 — the console's off-request login seam. When [LoginObserver] is present, the device
// flow reports its user code and verification link THROUGH it instead of printing to a terminal
// nobody is watching and opening a browser on the daemon's own (often headless) desktop — the same
// discipline OAuthLoginFlow's observer branch keeps for the browser flow. This mirrors
// DeviceLoginTokenlessTest's loopback fixture, observer-only.
package splice.oauth

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.provider.kimi.KimiOAuth
import splice.upstream.Waiter
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class DeviceLoginObserverTest {

    private class RecordingBrowserOpener : BrowserOpener {
        val urls = mutableListOf<String>()
        override fun open(url: String): Boolean {
            urls.add(url)
            return false
        }
    }

    private class RecordingObserver : LoginObserver {
        val detail = AtomicReference<LoginAnnouncement?>(null)
        override fun announced(detail: LoginAnnouncement) {
            this.detail.set(detail)
        }
    }

    private fun serving(tokenBody: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/device") { ex ->
            val body = """
                {"user_code":"WXYZ-1234","device_code":"dev-code",
                 "verification_uri":"http://127.0.0.1:${server.address.port}/verify","verification_uri_complete":"",
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

    private fun specFor(server: HttpServer, authPath: Path): DeviceLoginSpec {
        val oauth = KimiOAuth()
        return DeviceLoginSpec(
            head = "probe",
            clientId = "cid",
            deviceAuthUrl = "http://127.0.0.1:${server.address.port}/device",
            tokenUrl = "http://127.0.0.1:${server.address.port}/token",
            authPath = authPath,
            identityHeaders = emptyMap(),
            toAuthJson = { body ->
                val token = Regex(""""access_token"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1).orEmpty()
                """{"access_token":"$token"}"""
            },
            deviceAuthForm = DeviceAuthForm { oauth.kimiDeviceAuthorizationForm(it) },
            parseDeviceAuth = DeviceAuthParse { body ->
                val parsed = oauth.parseKimiDeviceAuthorization(body)
                DeviceAuthorization(
                    userCode = parsed.userCode,
                    deviceCode = parsed.deviceCode,
                    verificationUri = parsed.verificationUri,
                    verificationUriComplete = parsed.verificationUriComplete,
                    expiresInS = parsed.expiresInS,
                    intervalS = parsed.intervalS,
                )
            },
            tokenPollForm = TokenPollForm { code, id -> oauth.kimiTokenPollForm(code, id) },
        )
    }

    @Test
    fun `an observed device login reports the code through the observer, never the terminal or a browser`(
        @TempDir tmp: Path,
    ) {
        val authPath = tmp.resolve("auth.json")
        val server = serving("""{"access_token":"tok_observed"}""")
        val browser = RecordingBrowserOpener()
        val observer = RecordingObserver()
        val out = StringBuilder()
        val flow = DeviceLoginFlow(TerminalOutput { out.appendLine(it) }, browser)
        val ok = try {
            runBlocking { flow.run(specFor(server, authPath), Waiter {}, observer) }
        } finally {
            server.stop(0)
        }

        assertTrue(ok, out.toString())
        assertEquals("WXYZ-1234", observer.detail.get()?.userCode)
        assertEquals(
            "http://127.0.0.1:${server.address.port}/verify",
            observer.detail.get()?.verificationUri,
        )
        assertTrue(browser.urls.isEmpty(), "an observed login must never open a browser on the daemon's desktop")
        assertFalse(
            out.toString().contains("open the URL above"),
            "an observed login must not print to a terminal nobody is watching: $out",
        )
        assertTrue(Files.exists(authPath), "the credential is still persisted exactly as an unobserved login")
    }
}
