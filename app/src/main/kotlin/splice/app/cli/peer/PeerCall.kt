package splice.app.cli.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.cli.AdminSupport
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import splice.sessions.peer.codex.CodexNames
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// why: register waits up to 10 s for the helper's record (spec 3.2), and revoke up to 5 s before SIGKILL; a webhook
// test waits on the receiver's 10 s timeout. 20 s covers each with room for a loaded machine.
private const val CALL_TIMEOUT_MS = 20_000

private const val PEER_KEY_FILE_ENV = "SPLICE_PEER_KEY_FILE"

/**
 * One control-plane call for `splice peer`: which key it presents, and how the answer becomes an exit code. The
 * management verbs present the management key; the `--as` verbs present the peer's own key, read from its file and
 * sent only as a bearer header, never in argv or output.
 */
internal class PeerCall(
    private val env: EnvReader,
    private val err: TerminalOutput,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val codexNames = CodexNames()

    /** The management key, or null with the reason printed. */
    fun managementKey(): String? = when (val read = AdminSupport.readMgmtKey(env)) {
        is MgmtKeyRead.Present -> read.key
        MgmtKeyRead.Absent -> fail("no management key yet; the daemon mints it on first launch")
        is MgmtKeyRead.Unreadable -> fail("the management key is unreadable: ${read.reason}")
    }

    /** The peer key: `SPLICE_PEER_KEY_FILE` when set, else `<settings dir>/peers/<as>/peer.key`, where `--as` defaults,
     *  inside a Codex conversation, to that thread's codex peer. Null once printed. */
    fun peerKey(asOption: String?): String? {
        val named = env(PEER_KEY_FILE_ENV)?.let { Path.of(it) }
        val path = named ?: keyFileOf(asOption)
            ?: return fail("--as <name>, $PEER_KEY_FILE_ENV or a Codex conversation names the peer to act as")
        return try {
            Files.readString(path).trim().takeIf { it.isNotEmpty() } ?: fail("the peer key file $path is empty")
        } catch (_: NoSuchFileException) {
            fail(
                if (named == null) {
                    "${path.parent?.fileName} is not a registered peer: there is no key file at $path " +
                        "(splice peer list shows them)"
                } else {
                    "no registered peer has the key file $path: it was never registered, or it was revoked"
                },
            )
        } catch (unreadable: IOException) {
            fail("cannot read the peer key file $path: ${SafeFailureText.render(unreadable)}")
        }
    }

    /** `<settings dir>/peers/<as>/peer.key`, where `--as` defaults, inside a Codex conversation, to that thread's codex
     *  peer (docs/peers.md); null when neither names one. */
    private fun keyFileOf(asOption: String?): Path? =
        (asOption ?: env(CODEX_THREAD_ENV)?.let(codexNames::thread)?.let(codexNames::peer))
            ?.let { settingsDir().resolve("peers").resolve(it).resolve("peer.key") }

    /** `<settings dir>`: the folder holding splice.toml, where `peers/` lives. */
    fun settingsDir(): Path = KeyStorePath.defaultPath(env).toAbsolutePath().parent

    /** A call with no body (GET, DELETE, or a POST that carries nothing). */
    fun call(method: String, path: String, bearer: String): PeerAnswer =
        answer(ControlPlaneClient.send(url(path), method, bearer, CALL_TIMEOUT_MS))

    /** A call with a JSON body. A connection cut after the body went up may have acted, and says so. */
    fun call(method: String, path: String, bearer: String, body: JsonObject): PeerAnswer =
        when (val read = ControlPlaneClient.withBody(url(path), method, bearer, body.toString(), CALL_TIMEOUT_MS)) {
            is PostReading.Answered -> answer(read.reply)
            PostReading.NotConnected -> answer(null)
            PostReading.CutAfterSending -> {
                fail("the daemon stopped answering after the request went up, so it may have run")
                PeerAnswer.Exit(PeerExit.UNREACHABLE)
            }
        }

    private fun answer(reply: ControlReply?): PeerAnswer {
        if (reply == null) {
            fail("the daemon is not answering on :${AdminSupport.controlPort(env)}. Is it running? (splice status)")
            return PeerAnswer.Exit(PeerExit.UNREACHABLE)
        }
        val body = JsonScalars.objectOrNull(json, reply.body)
        if (reply.status in ControlPlaneClient.OK_RANGE) return PeerAnswer.Ok(body ?: JsonObject(emptyMap()))
        val reason = JsonScalars.str(body, "error") ?: "the daemon answered ${reply.status}"
        fail("$reason (${reply.status})")
        return PeerAnswer.Exit(
            when (reply.status) {
                HttpStatus.TOO_MANY_REQUESTS -> PeerExit.RATE_LIMITED
                HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE -> PeerExit.TRANSPORT
                else -> PeerExit.REFUSED
            },
        )
    }

    private fun url(path: String): String = "http://127.0.0.1:${AdminSupport.controlPort(env)}$path"

    private fun fail(message: String): String? {
        err.line("splice peer: $message")
        return null
    }
}
