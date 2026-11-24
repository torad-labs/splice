// Tests for `splice peer attach` (harness-agnostic owner-bound auto-registration + env/hook helper) and defaulting
// `--as` from `SPLICE_PEER_NAME` or an ancestor-owned peer in `splice peer send|inbox|status`.
package splice.app.cli.peer

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.hook.StreamTerminal
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class PeerAttachAndDefaultAsTest {
    @Test
    fun `attach --owner --env registers an owner-bound peer and prints shell exports`(@TempDir dir: Path) {
        val pid = ProcessHandle.current().pid()
        val keyPath = dir.resolve("peer.key").toAbsolutePath()
        val sockPath = dir.resolve("1.sock").toAbsolutePath()
        val capture = RequestCapture()
        val reply = """{"name":"opencode-$pid","sessionId":"s-1","keyFile":"$keyPath","socket":"$sockPath","pid":12}"""
        val ran = withServer(dir, StubReply(201, reply), capture) { env ->
            runPeer(listOf("attach", "--harness", "opencode", "--owner", pid.toString(), "--env"), env, terminal = true)
        }
        assertEquals(PeerExit.DONE, ran.code, ran.err)
        val lines = ran.out.trim().lines()
        assertEquals("export SPLICE_PEER_NAME='opencode-$pid'", lines[0])
        assertEquals("export SPLICE_PEER_KEY_FILE='$keyPath'", lines[1])
        val body = Json.parseToJsonElement(capture.body).jsonObject
        assertEquals("opencode-$pid", JsonScalars.str(body, "name"))
        assertEquals(pid.toString(), JsonScalars.str(body["owner"]?.jsonObject, "pid"))
    }

    @Test
    fun `attach in hook mode uses session_id suffix, writes no stdout, and never exits 2`(@TempDir dir: Path) {
        val pid = ProcessHandle.current().pid()
        val keyPath = dir.resolve("peer.key").toAbsolutePath()
        val capture = RequestCapture()
        val reply = """{"name":"agy-9d7c1d2e","sessionId":"s-1","keyFile":"$keyPath","socket":null,"pid":null}"""
        val session = "9d7c1d2e-1111-2222-3333-444444444444"
        val hookPayload = """{"hook_event_name":"SessionStart","session_id":"$session",""" +
            """"cwd":"${dir.toAbsolutePath()}"}"""
        val ran = withServer(dir, StubReply(201, reply), capture) { env ->
            runPeer(
                listOf("attach", "--harness", "agy", "--owner", pid.toString(), "--hook"),
                env,
                stdin = hookPayload,
                terminal = false,
            )
        }
        assertEquals(PeerExit.DONE, ran.code, ran.err)
        assertEquals("", ran.out)
        val body = Json.parseToJsonElement(capture.body).jsonObject
        assertEquals("agy-9d7c1d2e", JsonScalars.str(body, "name"))
        assertEquals(dir.toAbsolutePath().normalize().toString(), JsonScalars.str(body, "cwd"))

        val badOwnerHook = runPeer(listOf("attach", "--owner", "4194303999", "--hook"), envOf(dir, 1), terminal = false)
        assertEquals(PeerExit.REFUSED, badOwnerHook.code)
        assertEquals("", badOwnerHook.out)
    }

    @Test
    fun `attach refuses unowned anonymous runs and plain shells, and accepts explicit --name`(@TempDir dir: Path) {
        val noOwner = runPeer(listOf("attach"), envOf(dir, 1), terminal = true)
        assertEquals(PeerExit.BAD_USAGE, noOwner.code)
        assertTrue("--owner" in noOwner.err && "--name" in noOwner.err, noOwner.err)

        val noOwnerHook = runPeer(listOf("attach", "--hook"), envOf(dir, 1), terminal = false)
        assertEquals(PeerExit.REFUSED, noOwnerHook.code)
        assertEquals("", noOwnerHook.out)

        for (shell in listOf("bash", "zsh", "sh", "fish", "dash")) {
            val badShell = runPeer(
                listOf("attach", "--harness", shell, "--name", "bot"),
                envOf(dir, 1),
                terminal = true,
            )
            assertEquals(PeerExit.BAD_USAGE, badShell.code, "shell $shell should be refused")
            assertTrue("plain shell" in badShell.err, badShell.err)
        }

        val extras = PeerRegistrationBody.extraPrograms(
            EnvReader { if (it == PEER_HARNESS_PROGRAMS_ENV) "bash, opencode, zsh" else null },
        )
        assertEquals(setOf("opencode"), extras)

        val keyPath = dir.resolve("peer.key").toAbsolutePath()
        val capture = RequestCapture()
        val reply = """{"name":"mybot","sessionId":"s-2","keyFile":"$keyPath","socket":null,"pid":null}"""
        val named = withServer(dir, StubReply(201, reply), capture) { env ->
            runPeer(listOf("attach", "--name", "mybot"), env, terminal = true)
        }
        assertEquals(PeerExit.DONE, named.code, named.err)
        assertEquals("mybot", JsonScalars.str(Json.parseToJsonElement(capture.body).jsonObject, "name"))
    }

    @Test
    fun `send without --as defaults to SPLICE_PEER_NAME and explicit --as overrides inherited env`(@TempDir dir: Path) {
        val peers = Files.createDirectories(dir.resolve("peers"))
        val alphaKey = Files.createDirectories(peers.resolve("alpha")).resolve("peer.key")
        val betaKey = Files.createDirectories(peers.resolve("beta")).resolve("peer.key")
        Files.writeString(alphaKey, "splp_alpha_key\n")
        Files.writeString(betaKey, "splp_beta_key\n")

        val capture = RequestCapture()
        val reply = StubReply(202, """{"msgId":"m-1","to":{"name":"target","sessionId":"s-t"}}""")
        val fromEnv = withServer(dir, reply, capture) { base ->
            runPeer(listOf("send", "target", "hello"), base + ("SPLICE_PEER_NAME" to "alpha"), terminal = true)
        }
        assertEquals(PeerExit.DONE, fromEnv.code, fromEnv.err)
        assertEquals("Bearer splp_alpha_key", capture.auth)

        val explicitWins = withServer(dir, reply, capture) { base ->
            runPeer(
                listOf("send", "--as", "beta", "target", "hello"),
                base + mapOf("SPLICE_PEER_NAME" to "alpha", "SPLICE_PEER_KEY_FILE" to alphaKey.toString()),
                terminal = true,
            )
        }
        assertEquals(PeerExit.DONE, explicitWins.code, explicitWins.err)
        assertEquals("Bearer splp_beta_key", capture.auth)
    }

    @Test
    fun `send without --as or peer env falls back to an ancestor-owned peer`(@TempDir dir: Path) {
        val parentPid = ProcessHandle.current().parent().orElseThrow().pid()
        val parentOwner = checkNotNull(PeerOwnerProcesses().capture(parentPid))
        val store = PeerStore(dir)
        val request = PeerRequest("agy-owned", dir.toAbsolutePath().toString(), listOf("*"), parentOwner)
        val registered = runBlocking { store.register(request, emptySet()) }
        assertTrue(registered is PeerRegistration.Registered)
        val expectedKey = Files.readString(store.keyFile("agy-owned")).trim()

        val capture = RequestCapture()
        val reply = StubReply(202, """{"msgId":"m-2","to":{"name":"target","sessionId":"s-t"}}""")
        val ran = withServer(dir, reply, capture) { base ->
            runPeer(listOf("send", "target", "from ancestor"), base, terminal = true)
        }
        assertEquals(PeerExit.DONE, ran.code, ran.err)
        assertEquals("Bearer $expectedKey", capture.auth)
    }

    private fun runPeer(
        args: List<String>,
        env: Map<String, String>,
        stdin: String = "",
        terminal: Boolean = true,
    ): PeerRun {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = runBlocking {
            PeerCommand(
                out = TerminalOutput { out.appendLine(it) },
                err = TerminalOutput { err.appendLine(it) },
                env = EnvReader { env[it] },
                stdin = PeerStdin { stdin },
                terminal = StreamTerminal { terminal },
            ).run(args)
        }
        return PeerRun(code, out.toString(), err.toString())
    }

    private fun withServer(
        dir: Path,
        reply: StubReply,
        capture: RequestCapture,
        block: (Map<String, String>) -> PeerRun,
    ): PeerRun {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            capture.auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            capture.body = exchange.requestBody.readBytes().decodeToString()
            val bytes = reply.body.encodeToByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return try {
            block(envOf(dir, server.address.port))
        } finally {
            server.stop(0)
        }
    }

    private fun envOf(dir: Path, port: Int): Map<String, String> {
        val state = Files.createDirectories(dir.resolve("state"))
        Files.writeString(state.resolve("mgmt-key"), "test-mgmt-key")
        val config = dir.resolve("splice.toml")
        Files.writeString(config, "[daemon]\nstate_dir = \"${state.toAbsolutePath()}\"\n")
        return mapOf(
            "SPLICE_CONFIG" to config.toAbsolutePath().toString(),
            "SPLICE_STATE_DIR" to state.toAbsolutePath().toString(),
            "SPLICE_CONTROL_PORT" to port.toString(),
        )
    }

    private data class StubReply(val status: Int, val body: String)

    private class RequestCapture {
        var auth: String = ""
        var body: String = ""
    }

    private data class PeerRun(val code: Int, val out: String, val err: String)
}
