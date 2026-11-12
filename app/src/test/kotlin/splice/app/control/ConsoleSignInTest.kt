// NEW: Oct 26, 2026 — the console signs in with no secret in any address. `splice console` asks the daemon for a
// single-use code with the management key, the page it writes carries only that code, and the console trades it once
// for the console key. A code that is spent, expired or never minted opens nothing, and the trade never answers the
// management key. Proven over real HTTP against a real ControlServer; the clock the codes' lifetimes are read from is
// the one fake.
package splice.app.control

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

/** How long a code signs in for, as the goal states it: two minutes from the moment `splice console` asked. */
private const val LIFETIME_MS = 2 * 60_000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleSignInTest {

    private val client = HttpClient(CIO) { expectSuccess = false }

    /** The instant the daemon reads; only the lifetime test moves it. */
    @Volatile
    private var now = 1_800_000_000_000L
    private lateinit var control: ControlServer
    private lateinit var mgmtKey: String
    private lateinit var turnKey: String

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        val paths = StatePaths(baseOverride = tempDir.resolve("state"))
        val mgmt = MgmtKey(paths, log = {})
        mgmtKey = mgmt.get()
        turnKey = TurnKey(mgmt).get()
        control = serverOn(paths, mgmt)
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `a code splice console asks for signs the console in once, with a key that is not the management key`() =
        runBlocking {
            val code = mint(control, mgmtKey)
            val first = trade(control, code)
            assertEquals(HttpStatusCode.OK, first.status)
            val key = checkNotNull(keyIn(first)) { "the trade answers the console key" }
            assertNotEquals(mgmtKey, key)
            assertNotEquals(turnKey, key)
            assertNotEquals(code, key)
            val reading = read(control, "/api/status", key)
            assertEquals(HttpStatusCode.OK, reading.status, "the console reads splice with it")

            val again = trade(control, code)
            assertEquals(HttpStatusCode.Unauthorized, again.status, "a spent code")
            assertNull(keyIn(again), "a spent code answers no key")
        }

    @Test
    fun `a code still signs in just inside its two minutes, and opens nothing once they are up`() = runBlocking {
        val inTime = mint(control, mgmtKey)
        val late = mint(control, mgmtKey)
        now += LIFETIME_MS - 1
        assertEquals(HttpStatusCode.OK, trade(control, inTime).status, "a code traded within its two minutes")
        now += 1
        val expired = trade(control, late)
        assertEquals(HttpStatusCode.Unauthorized, expired.status, "a code traded once its two minutes are up")
        assertNull(keyIn(expired))
    }

    @Test
    fun `only a caller holding the management key is handed a code`() = runBlocking {
        for (bearer in listOf(null, turnKey, "not-the-key")) {
            val reply = client.post("${base(control)}/api/console/code") {
                bearer?.let { header("Authorization", "Bearer $it") }
            }
            assertEquals(HttpStatusCode.Unauthorized, reply.status, "bearer: ${bearer?.take(4)}")
            assertNull(field(reply, "code"))
        }
    }

    @Test
    fun `a code splice never minted opens nothing, whatever its shape`() = runBlocking {
        val bodies = listOf(
            """{"code":"${"0".repeat(64)}"}""",
            """{"code":""}""",
            """{"code":null}""",
            """{"code":42}""",
            "{}",
            "not json",
            """{"code":"$mgmtKey"}""",
            """{"code":"$turnKey"}""",
        )
        for (body in bodies) {
            val reply = client.post("${base(control)}/api/console/key") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.Unauthorized, reply.status, body.take(12))
            assertNull(keyIn(reply), body.take(12))
        }
    }

    // Two tabs, or a tab and a tool, can load one address at the same instant: one of them is signed in, never both.
    @Test
    fun `however many trades race for one code, exactly one is answered a key`() = runBlocking {
        repeat(RACES) { round ->
            val code = mint(control, mgmtKey)
            val replies = List(RACERS) { async { trade(control, code) } }.awaitAll()
            assertEquals(1, replies.count { it.status == HttpStatusCode.OK }, "round $round")
            assertEquals(1, replies.mapNotNull { keyIn(it) }.size, "round $round")
        }
    }

    // A leaked management key is cut off by replacing it, and the daemon reads its key once, so the new key meets the
    // next daemon. The console key the old one issued opens nothing there, and the new daemon signs a console in anew.
    @Test
    fun `a console key issued under a replaced management key opens nothing`(@TempDir dir: Path) = runBlocking {
        val paths = StatePaths(baseOverride = dir.resolve("state"))
        val first = MgmtKey(paths, log = {})
        val before = serverOn(paths, first)
        before.start()
        val oldKey = try {
            checkNotNull(keyIn(trade(before, mint(before, first.get()))))
        } finally {
            before.stop()
        }

        Files.writeString(paths.mgmtKeyFile, "${"5e".repeat(32)}\n")
        val replaced = MgmtKey(paths, log = {})
        val after = serverOn(paths, replaced)
        after.start()
        try {
            assertNotEquals(first.get(), replaced.get(), "the management key was replaced")
            assertEquals(HttpStatusCode.Unauthorized, read(after, "/api/status", oldKey).status, "the old console key")
            val newKey = checkNotNull(keyIn(trade(after, mint(after, replaced.get()))))
            assertNotEquals(oldKey, newKey)
            assertEquals(HttpStatusCode.OK, read(after, "/api/status", newKey).status, "the console signed in anew")
        } finally {
            after.stop()
        }
    }

    // The same cut, made in place: the running daemon replaces its key. Every console signed in before is signed out,
    // and a code minted before, still unspent, opens nothing after: a code taken with the old key just before the
    // replacement would otherwise carry whoever took it across it.
    @Test
    fun `replacing the management key in place signs every console out, and no code from before signs one in`(
        @TempDir dir: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = dir.resolve("state"))
        val mgmt = MgmtKey(paths, log = {})
        val server = serverOn(paths, mgmt)
        server.start()
        try {
            val signedIn = checkNotNull(keyIn(trade(server, mint(server, mgmt.get()))))
            val unspent = mint(server, mgmt.get())
            mgmt.replace()
            assertEquals(HttpStatusCode.Unauthorized, read(server, "/api/status", signedIn).status, "signed in before")
            val late = trade(server, unspent)
            assertEquals(HttpStatusCode.Unauthorized, late.status, "a code minted before the replacement")
            assertNull(keyIn(late), "a code minted before the replacement answers no key")
            val key = checkNotNull(keyIn(trade(server, mint(server, mgmt.get()))))
            assertEquals(HttpStatusCode.OK, read(server, "/api/status", key).status, "a code minted after signs in")
        } finally {
            server.stop()
        }
    }

    private fun serverOn(paths: StatePaths, mgmt: MgmtKey): ControlServer = controlServerFor(
        port = 0,
        heads = emptyMap(),
        config = ConfigService(paths),
        auth = ControlAuth(mgmtKey = mgmt, log = {}, clock = WallClock { now }),
    )

    private fun base(server: ControlServer): String = "http://127.0.0.1:${server.listeningPort}"

    /** What `splice console` does first: ask for a code with the management key. */
    private suspend fun mint(server: ControlServer, management: String): String {
        val reply = client.post("${base(server)}/api/console/code") { header("Authorization", "Bearer $management") }
        assertEquals(HttpStatusCode.OK, reply.status, "the management key is handed a code")
        return checkNotNull(field(reply, "code")) { "the answer carries a code" }
    }

    /** What the console page does with the code its address carried: trade it, in the body, with no key. */
    private suspend fun trade(server: ControlServer, code: String): HttpResponse =
        client.post("${base(server)}/api/console/key") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":"$code"}""")
        }

    private suspend fun read(server: ControlServer, path: String, key: String): HttpResponse =
        client.get("${base(server)}$path") { header("Authorization", "Bearer $key") }

    private suspend fun keyIn(reply: HttpResponse): String? = field(reply, "key")

    /** A string field of a JSON answer, or null when the answer is not JSON or carries none. */
    private suspend fun field(reply: HttpResponse, name: String): String? {
        val body = try {
            Json.parseToJsonElement(reply.bodyAsText()) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        return (body?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}

/** Codes raced, and the trades racing for each. */
private const val RACES = 20
private const val RACERS = 8
