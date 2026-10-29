// NEW: Oct 29, 2026. Temple's North Star journal asked to read a sent message's receipt through the public command
// rather than its own control-port client: `splice peer status --as <name> <msgId> --json` prints the record the
// daemon stored for that send, its id, target, outcome and receipt, exactly as stored, and sends and changes nothing.
// A read the daemon refuses leaves stdout empty and exits non-zero, so a caller never reads a refusal as a receipt.
// Without --json the command prints its text lines as before.
package splice.app.cli.peer

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.hook.StdinTerminal
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.wire.HttpStatus
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class PeerStatusJsonTest {

    @Test
    fun `--json prints the stored send and its refused receipt as they are, after one read`(@TempDir dir: Path) {
        val seen = mutableListOf<Seen>()
        val ran = withSent(dir, HttpStatus.OK, REFUSED_RECORD, seen) { env ->
            runStatus(listOf("status", "--as", PEER, MSG_ID, "--json"), env)
        }
        assertEquals(PeerExit.DONE, ran.code, ran.err)
        val printed = ran.out.trimEnd().lines()
        assertEquals(1, printed.size, ran.out)
        assertEquals(statusJson.parseToJsonElement(REFUSED_RECORD), statusJson.parseToJsonElement(printed.single()))
        val read = seen.single()
        assertEquals("GET", read.method)
        assertEquals("/api/peers/self/sent/$MSG_ID", read.path)
        assertEquals("Bearer $PEER_KEY", read.authorization)
        assertEquals("", read.body)
    }

    @Test
    fun `--json prints a send with no receipt yet with its receipt null, never as delivered`(@TempDir dir: Path) {
        val ran = withSent(dir, HttpStatus.OK, UNANSWERED_RECORD, mutableListOf()) { env ->
            runStatus(listOf("status", "--as", PEER, MSG_ID, "--json"), env)
        }
        assertEquals(PeerExit.DONE, ran.code, ran.err)
        val printed = statusJson.parseToJsonElement(ran.out.trim()) as JsonObject
        assertEquals(statusJson.parseToJsonElement(UNANSWERED_RECORD), printed)
    }

    @Test
    fun `a read the daemon refuses prints nothing on stdout and exits non-zero`(@TempDir dir: Path) {
        val ran = withSent(dir, HttpStatus.NOT_FOUND, NOT_FOUND_BODY, mutableListOf()) { env ->
            runStatus(listOf("status", "--as", PEER, MSG_ID, "--json"), env)
        }
        assertNotEquals(PeerExit.DONE, ran.code)
        assertEquals("", ran.out)
        assertTrue(ran.err.contains("this peer sent no message with that id"), ran.err)
    }

    @Test
    fun `without --json the status prints its text lines, not the record`(@TempDir dir: Path) {
        val ran = withSent(dir, HttpStatus.OK, REFUSED_RECORD, mutableListOf()) { env ->
            runStatus(listOf("status", "--as", PEER, MSG_ID), env)
        }
        assertEquals(PeerExit.DONE, ran.code, ran.err)
        val lines = ran.out.trimEnd().lines()
        assertEquals("submitted", lines[0])
        assertTrue(lines[1].startsWith("refused (rate limit) at "), ran.out)
    }
}

private fun runStatus(args: List<String>, env: Map<String, String>): StatusRun {
    val out = StringBuilder()
    val err = StringBuilder()
    val code = runBlocking {
        PeerCommand(
            out = TerminalOutput { out.appendLine(it) },
            err = TerminalOutput { err.appendLine(it) },
            env = EnvReader { env[it] },
            stdin = PeerStdin { "" },
            terminal = StdinTerminal { false },
        ).run(args)
    }
    return StatusRun(code, out.toString(), err.toString())
}

/** A control port that answers every request with [status] and [body], noting each request in [seen]. */
private fun withSent(
    dir: Path,
    status: Int,
    body: String,
    seen: MutableList<Seen>,
    block: (Map<String, String>) -> StatusRun,
): StatusRun {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        seen += Seen(
            exchange.requestMethod,
            exchange.requestURI.rawPath,
            exchange.requestHeaders.getFirst("Authorization"),
            exchange.requestBody.readBytes().decodeToString(),
        )
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return try {
        block(peerEnv(dir, server.address.port))
    } finally {
        server.stop(0)
    }
}

private fun peerEnv(dir: Path, port: Int): Map<String, String> {
    val state = Files.createDirectories(dir.resolve("state"))
    val config = dir.resolve("splice.toml")
    Files.writeString(config, "[daemon]\nstate_dir = \"${state.toAbsolutePath()}\"\n")
    val keyFile = Files.createDirectories(dir.resolve("peers").resolve(PEER)).resolve("peer.key")
    Files.writeString(keyFile, "$PEER_KEY\n")
    return mapOf(
        "SPLICE_CONFIG" to config.toAbsolutePath().toString(),
        "SPLICE_CONTROL_PORT" to port.toString(),
        "SPLICE_STATE_DIR" to state.toAbsolutePath().toString(),
        "SPLICE_PEER_KEY_FILE" to keyFile.toAbsolutePath().toString(),
    )
}

private data class StatusRun(val code: Int, val out: String, val err: String)

private data class Seen(val method: String, val path: String, val authorization: String?, val body: String)

private val statusJson: Json = Json

private const val PEER: String = "north-star"
private const val PEER_KEY: String = "peer-key-for-north-star"
private const val MSG_ID: String = "temple-op-7f3a9c"
private const val REFUSED_RECORD: String =
    """{"msgId":"$MSG_ID","to":"uds:/run/user/1000/cc-socks/42.sock","at":1761750000000,"outcome":"submitted",""" +
        """"receipt":{"status":"refused","detail":"rate limit","at":1761750001000}}"""
private const val UNANSWERED_RECORD: String =
    """{"msgId":"$MSG_ID","to":"uds:/run/user/1000/cc-socks/42.sock","at":1761750000000,"outcome":"submitted",""" +
        """"receipt":null}"""
private const val NOT_FOUND_BODY: String = """{"error":"this peer sent no message with that id"}"""
