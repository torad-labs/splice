// NEW: Oct 27, 2026. Codex runs `splice peer codex attach|detach` as a hook and passes the thread as
// `session_id` on stdin (codex-rs/hooks/src/schema.rs, rust-v0.159.0). Exit 2 blocks the turn, and any
// stdout is context or a decision. The payload, a missing id, and a daemon that cannot be reached
// each leave with some other status and an empty stdout.
package splice.app.cli.peer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.hook.StdinKind
import splice.app.cli.hook.StdinTerminal
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class PeerCodexHookTest {

    @Test
    fun `a pipe is not a terminal and a pts device is`() {
        assertFalse(StdinKind.terminal("pipe:[18421]"))
        assertFalse(StdinKind.terminal("socket:[9]"))
        assertFalse(StdinKind.terminal("/dev/null"))
        assertTrue(StdinKind.terminal("/dev/pts/4"))
        assertTrue(StdinKind.terminal("/dev/tty"))
    }

    @Test
    fun `stdin payloads name the thread and cwd and write nothing`(@TempDir dir: Path) {
        val hits = mutableListOf<Hit>()
        withDaemon(dir, handler = { exchange ->
            hits += Hit(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestURI.query,
                exchange.requestBody.readBytes().decodeToString(),
            )
            if (exchange.requestMethod == "DELETE") "{}".encodeToByteArray() else ATTACHED.encodeToByteArray()
        }) { env ->
            val prompt = hook(USER_PROMPT, extra = promptFields, prompt = "line\nwith \"quotes\"")
            val started = hook(SESSION_START, cwd = OTHER, extra = mapOf("source" to "startup"))
            assertQuiet(run(verb(ATTACH), prompt, env))
            assertQuiet(run(verb(ATTACH, "--json"), started, env))
            assertQuiet(run(verb(DETACH), hook(SESSION_END, extra = mapOf("reason" to "other")), env))
        }
        val posts = hits.filter { it.method == "POST" }
        val deleted = hits.single { it.method == "DELETE" }
        assertEquals(2, posts.size)
        posts.forEach { hit ->
            assertEquals(CODEX_PATH, hit.path)
            assertEquals(THREAD, field(hit.body, "thread"))
        }
        assertEquals(absolute(CWD), field(posts[0].body, "cwd"))
        assertEquals(absolute(OTHER), field(posts[1].body, "cwd"))
        assertEquals("/api/peers/codex-$THREAD", deleted.path)
        assertEquals("purge=false", deleted.query)
    }

    @Test
    fun `a missing id is a no-op and never usage`(@TempDir dir: Path) {
        val env = daemonEnv(dir, closedPort())
        val absent = run(verb(ATTACH), hook(USER_PROMPT, session = null), env)
        val empty = run(verb(DETACH), "", env)
        val garbage = run(verb(ATTACH), "not json", env)
        val foreign = run(verb(ATTACH), hook(USER_PROMPT, session = "thr_123"), env)
        listOf(absent, empty, garbage, foreign).forEach { ran ->
            assertEquals(PeerExit.DONE, ran.code)
            assertEquals("", ran.out)
            assertFalse(ran.err.contains(USAGE), ran.err)
        }
        assertTrue(absent.err.contains("no Codex thread id"), absent.err)
    }

    @Test
    fun `a hook whose daemon is down does not exit 2`(@TempDir dir: Path) {
        val env = daemonEnv(dir, closedPort())
        val attach = run(verb(ATTACH), hook(USER_PROMPT), env)
        val detach = run(verb(DETACH), hook(SESSION_END), env)
        assertEquals(PeerExit.UNREACHABLE, attach.code)
        assertEquals(PeerExit.UNREACHABLE, detach.code)
        assertEquals("", attach.out)
        assertEquals("", detach.out)
        assertTrue(attach.err.contains("not answering"), attach.err)
        assertTrue(detach.err.contains("not answering"), detach.err)
    }

    @Test
    fun `a refusal and bad flags do not exit 2`(@TempDir dir: Path) {
        withDaemon(dir, status = HttpStatus.NOT_FOUND, handler = { exchange ->
            val _ = exchange.requestBody.readBytes()
            """{"error":"no such peer"}""".encodeToByteArray()
        }) { env ->
            val attach = run(verb(ATTACH), hook(USER_PROMPT), env)
            val detach = run(verb(DETACH), hook(SESSION_END), env)
            assertEquals(PeerExit.REFUSED, attach.code)
            assertEquals(PeerExit.REFUSED, detach.code)
            assertEquals("", attach.out)
            assertEquals("", detach.out)
            assertTrue(attach.err.contains(HttpStatus.NOT_FOUND.toString()), attach.err)
        }
        val flags = run(verb(ATTACH, "--nope"), hook(USER_PROMPT), daemonEnv(dir, closedPort()))
        assertEquals(PeerExit.REFUSED, flags.code)
        assertEquals("", flags.out)
        assertFalse(flags.err.contains(USAGE), flags.err)
    }

    @Test
    fun `an operator at a terminal with no thread gets usage and stdin stays unread`() {
        var read = false
        val stdout = StringBuilder()
        val err = StringBuilder()
        val code = runBlocking {
            PeerCommand(
                out = TerminalOutput { stdout.appendLine(it) },
                err = TerminalOutput { err.appendLine(it) },
                env = EnvReader { null },
                stdin = PeerStdin {
                    read = true
                    ""
                },
                terminal = StdinTerminal { true },
            ).run(verb(ATTACH))
        }
        assertEquals(PeerExit.BAD_USAGE, code)
        assertEquals("", stdout.toString())
        assertFalse(read)
        assertTrue(err.toString().contains("usage: splice peer"), err.toString())
    }

    @Test
    fun `hook at a terminal leaves the session alone and reads no stdin`() {
        var read = false
        val err = StringBuilder()
        val code = runBlocking {
            PeerCommand(
                out = TerminalOutput { error("a hook must not write stdout") },
                err = TerminalOutput { err.appendLine(it) },
                env = EnvReader { null },
                stdin = PeerStdin {
                    read = true
                    ""
                },
                terminal = StdinTerminal { true },
            ).run(verb(ATTACH, "--hook"))
        }
        assertEquals(PeerExit.DONE, code)
        assertFalse(read)
        assertTrue(err.toString().contains("no Codex thread id"), err.toString())
    }

    @Test
    fun `an argument and --cwd win over the payload and stdout stays empty`(@TempDir dir: Path) {
        val hits = mutableListOf<Hit>()
        withDaemon(dir, handler = { exchange ->
            hits += Hit(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestURI.query,
                exchange.requestBody.readBytes().decodeToString(),
            )
            "{}".encodeToByteArray()
        }) { env ->
            val args = verb(ATTACH, OTHER_THREAD, "--cwd", FLAG_CWD)
            assertQuiet(run(args, hook(USER_PROMPT), env))
        }
        val body = hits.single().body
        assertEquals(OTHER_THREAD, field(body, "thread"))
        assertEquals(absolute(FLAG_CWD), field(body, "cwd"))
    }
}

private fun assertQuiet(ran: Ran) {
    assertEquals(PeerExit.DONE, ran.code, ran.err)
    assertEquals("", ran.out)
    assertEquals("", ran.err)
}

private fun hook(
    event: String,
    session: String? = THREAD,
    cwd: String? = CWD,
    extra: Map<String, String> = emptyMap(),
    prompt: String? = null,
): String = buildJsonObject {
    if (session != null) put("session_id", session)
    put("transcript_path", JsonNull)
    if (cwd != null) put("cwd", cwd)
    put("hook_event_name", event)
    extra.forEach { (key, value) -> put(key, value) }
    if (prompt != null) put("prompt", prompt)
}.toString()

private fun run(args: List<String>, stdin: String, env: Map<String, String>): Ran {
    val out = StringBuilder()
    val err = StringBuilder()
    val code = runBlocking {
        PeerCommand(
            out = TerminalOutput { out.appendLine(it) },
            err = TerminalOutput { err.appendLine(it) },
            env = EnvReader { env[it] },
            stdin = PeerStdin { stdin },
            terminal = StdinTerminal { false },
        ).run(args)
    }
    return Ran(code, out.toString(), err.toString())
}

private fun withDaemon(
    dir: Path,
    status: Int = HttpStatus.OK,
    handler: (HttpExchange) -> ByteArray,
    block: (Map<String, String>) -> Unit,
) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val body = handler(exchange)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
        block(daemonEnv(dir, server.address.port))
    } finally {
        server.stop(0)
    }
}

private fun verb(name: String, vararg rest: String): List<String> = listOf(CODEX, name, *rest)

private fun field(body: String, key: String): String? =
    JsonScalars.str(JsonScalars.objectOrNull(json, body), key)

private fun absolute(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()

private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

private fun daemonEnv(dir: Path, port: Int): Map<String, String> {
    val state = Files.createDirectories(dir.resolve("state"))
    Files.writeString(state.resolve("mgmt-key"), "test-mgmt-key")
    val config = dir.resolve("splice.toml")
    Files.writeString(config, "[daemon]\nstate_dir = \"${state.toAbsolutePath()}\"\n")
    return mapOf(
        "SPLICE_CONFIG" to config.toAbsolutePath().toString(),
        "SPLICE_CONTROL_PORT" to port.toString(),
        "SPLICE_STATE_DIR" to state.toAbsolutePath().toString(),
    )
}

private data class Ran(val code: Int, val out: String, val err: String)

private data class Hit(val method: String, val path: String, val query: String?, val body: String)

private val json: Json = Json { ignoreUnknownKeys = true }

private val promptFields: Map<String, String> = mapOf(
    "turn_id" to "turn-1",
    "model" to "gpt-5.1-codex",
    "permission_mode" to "default",
)

private const val CODEX: String = "codex"
private const val ATTACH: String = "attach"
private const val DETACH: String = "detach"
private const val USER_PROMPT: String = "UserPromptSubmit"
private const val SESSION_START: String = "SessionStart"
private const val SESSION_END: String = "SessionEnd"
private const val USAGE: String = "usage:"
private const val CODEX_PATH: String = "/api/peers/codex"
private const val FLAG_CWD: String = "/from/flag"
private const val THREAD: String = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e5f"
private const val ATTACHED: String = """{"name":"codex-$THREAD","sessionId":"$THREAD"}"""
private const val OTHER_THREAD: String = "019a2f6c-4b1e-7c3d-9e8f-0a1b2c3d4e60"
private const val CWD: String = "/work/repo"
private const val OTHER: String = "/work/other"
