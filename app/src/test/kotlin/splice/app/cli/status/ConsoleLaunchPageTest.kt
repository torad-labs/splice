// `splice console` opens the console signed in, with no secret in any address (Oct 26, 2026). It asks the running
// daemon for a single-use code with the management key, writes an owner-only page that sends the browser to the console
// with that code in the address fragment, and sends the browser to that page. The page, its address and every line the
// command prints hold no key; the code inside signs a console in once; and no page an earlier splice wrote with the
// management key in its redirect outlives the run. Driven against a real ControlServer. The browser is the one fake: a
// test never opens the desktop's.
package splice.app.cli.status

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ControlAuth
import splice.app.control.ControlServer
import splice.app.control.controlServerFor
import splice.core.config.CONSOLE_LAUNCH_PAGE
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.daemonclient.MgmtKeyRead
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleLaunchPageTest {

    private val client = HttpClient(CIO) { expectSuccess = false }
    private lateinit var control: ControlServer
    private lateinit var mgmtKey: String
    private lateinit var turnKey: String
    private val port: Int get() = control.listeningPort
    private val opened = mutableListOf<String>()
    private val said = mutableListOf<String>()
    private val command = ConsoleCommand(browser = { opened += it; true }, out = { said += it })

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        val paths = StatePaths(baseOverride = tempDir.resolve("state"))
        val mgmt = MgmtKey(paths, log = {})
        mgmtKey = mgmt.get()
        turnKey = TurnKey(mgmt).get()
        control = controlServerFor(0, emptyMap(), ConfigService(paths), ControlAuth(mgmtKey = mgmt, log = {}))
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `splice console sends the browser to an owner-only page that carries a single-use code and no key`(
        @TempDir pages: Path,
    ) {
        val dashboardPage = pages.resolve("dashboard-open.html")
        Files.writeString(dashboardPage, keyedPage())

        assertTrue(command.openOn(port, MgmtKeyRead.Present(mgmtKey), pages, openBrowser = true))

        val page = pages.resolve(CONSOLE_LAUNCH_PAGE)
        assertEquals(listOf(page.toUri().toString()), opened, "the browser is sent to the page, in one step")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(page)))
        val html = Files.readString(page)
        assertFalse(html.contains(mgmtKey), "the page holds no management key")
        assertFalse(html.contains(turnKey), "the page holds no turn key")
        val code = codeIn(page)
        assertNotEquals(mgmtKey, code)
        assertTrue(said.none { it.contains(code) || it.contains(mgmtKey) }, "nothing printed signs in")
        assertFalse(Files.exists(dashboardPage), "the page the removed dashboard left, with the key in it, is gone")

        val traded = trade(code)
        assertEquals(HttpStatusCode.OK, traded.status, "the page's code signs the console in")
        val key = checkNotNull(field(traded, "key"))
        assertEquals(HttpStatusCode.OK, status(key), "the key it trades for reads splice")
        assertEquals(HttpStatusCode.Unauthorized, trade(code).status, "and only once")
    }

    // A session that drives the console with a browser tool opens the page itself, so no desktop browser may take the
    // code first. The address it prints is the page's own file, which signs nothing in.
    @Test
    fun `with --no-browser it prints the page's address for a browser tool, and nothing that signs in`(
        @TempDir pages: Path,
    ) {
        assertTrue(command.openOn(port, MgmtKeyRead.Present(mgmtKey), pages, openBrowser = false))

        assertEquals(emptyList<String>(), opened, "no desktop browser opens")
        val page = pages.resolve(CONSOLE_LAUNCH_PAGE)
        assertTrue(said.any { it.contains(page.toUri().toString()) }, said.toString())
        val code = codeIn(page)
        assertTrue(said.none { it.contains(code) || it.contains(mgmtKey) }, "nothing printed signs in")
        assertEquals(HttpStatusCode.OK, trade(code).status, "the page's code signs a console in")
    }

    // Between an install and its restart, a new splice meets the daemon that came before it, which hands out no code.
    @Test
    fun `against a daemon that hands out no code, it writes no page and never falls back to the key`(
        @TempDir pages: Path,
    ) {
        val stalePage = pages.resolve(CONSOLE_LAUNCH_PAGE)
        Files.writeString(stalePage, keyedPage())
        val older = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        older.createContext("/") { exchange ->
            exchange.sendResponseHeaders(HttpStatusCode.NotFound.value, -1)
            exchange.close()
        }
        older.start()
        try {
            assertFalse(command.openOn(older.address.port, MgmtKeyRead.Present(mgmtKey), pages, openBrowser = true))
        } finally {
            older.stop(0)
        }
        assertEquals(emptyList<String>(), opened, "no browser opens")
        assertFalse(Files.exists(stalePage), "the page an earlier splice wrote with the key in it is gone")
        assertTrue(said.any { it.contains("splice restart") }, said.toString())
        assertTrue(said.none { it.contains(mgmtKey) }, said.toString())
    }

    // The page is markup a browser opens, so what an answer puts in it is a plain code or nothing: something else that
    // took the control port cannot write a script into the page.
    @Test
    fun `an answer whose code is not a plain code writes no page and opens nothing`(@TempDir pages: Path) {
        val forged = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        forged.createContext("/") { exchange ->
            val body = """{"code":"x\"><script>alert(1)</script>"}""".toByteArray()
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        forged.start()
        try {
            assertFalse(command.openOn(forged.address.port, MgmtKeyRead.Present(mgmtKey), pages, openBrowser = true))
        } finally {
            forged.stop(0)
        }
        assertEquals(emptyList<String>(), opened, "no browser opens")
        assertFalse(Files.exists(pages.resolve(CONSOLE_LAUNCH_PAGE)), "no page is written")
    }

    @BeforeEach
    fun clear() {
        opened.clear()
        said.clear()
    }

    private fun base(): String = "http://127.0.0.1:$port"

    /** A launch page as splice wrote it before Oct 26: the management key in the redirect's fragment. */
    private fun keyedPage(): String =
        "<!doctype html><meta http-equiv=\"refresh\" content=\"0;url=http://127.0.0.1:3096/#k=$mgmtKey\">"

    /** The code in the page's redirect, which goes to this daemon's console and carries nothing else. */
    private fun codeIn(page: Path): String {
        val html = Files.readString(page)
        val target = Regex("""url=http://127\.0\.0\.1:$port/#c=([^"&/]+)"""").find(html)
        return checkNotNull(target?.groupValues?.get(1)) { "the page sends the browser to this console with a code" }
    }

    private fun trade(code: String): HttpResponse = runBlocking {
        client.post("${base()}/api/console/key") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }
    }

    private fun status(key: String): HttpStatusCode = runBlocking {
        client.get("${base()}/api/status") { header("Authorization", "Bearer $key") }.status
    }

    private fun field(reply: HttpResponse, name: String): String? = runBlocking {
        val body = Json.parseToJsonElement(reply.bodyAsText()) as? JsonObject
        (body?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
