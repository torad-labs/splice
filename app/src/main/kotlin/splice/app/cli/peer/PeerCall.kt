package splice.app.cli.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.cli.AdminSupport
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import splice.sessions.peer.codex.CodexNames
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// why: register waits up to 10 s for the helper's record (spec 3.2), and revoke up to 5 s before SIGKILL; a webhook
// test waits on the receiver's 10 s timeout. 20 s covers each with room for a loaded machine.
internal const val CALL_TIMEOUT_MS = 20_000

internal const val PEER_KEY_FILE_ENV: String = "SPLICE_PEER_KEY_FILE"
internal const val PEER_NAME_ENV: String = "SPLICE_PEER_NAME"

/**
 * One control-plane call for `splice peer`: which key it presents, and how the answer becomes an exit code. The
 * management verbs present the management key; the `--as` verbs present the peer's own key, read from its file and
 * sent only as a bearer header, never in argv or output.
 */
internal class PeerCall(
    private val env: EnvReader,
    private val err: TerminalOutput,
    /** The verb whose name every refusal carries: `splice server` makes its calls through this class too. */
    private val verb: String = "peer",
    private val ownerProcesses: PeerOwnerProcesses = PeerOwnerProcesses(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val codexNames = CodexNames()

    /** The management key, or null with the reason printed. */
    fun managementKey(): String? = when (val read = AdminSupport.readMgmtKey(env)) {
        is MgmtKeyRead.Present -> read.key
        MgmtKeyRead.Absent -> fail("no management key yet; the daemon mints it on first launch")
        is MgmtKeyRead.Unreadable -> fail("the management key is unreadable: ${read.reason}")
    }

    /** The peer key: explicit `--as <name>` first, else `SPLICE_PEER_KEY_FILE`, else `SPLICE_PEER_NAME`, else an
     *  ancestor-owned peer registered in `<settings dir>/peers/`, else `CODEX_THREAD_ID`. Null once printed. */
    fun peerKey(asOption: String?): String? {
        val explicit = asOption?.takeIf(String::isNotBlank)
        val envKeyFile = if (explicit == null) {
            env(PEER_KEY_FILE_ENV)?.takeIf(String::isNotBlank)?.let(Path::of)
        } else {
            null
        }
        val path = envKeyFile ?: keyFileOf(explicit)
            ?: return fail(
                "--as <name>, $PEER_KEY_FILE_ENV, $PEER_NAME_ENV, an owner-bound harness peer, or a Codex " +
                    "conversation names the peer to act as",
            )
        return try {
            Files.readString(path).trim().takeIf { it.isNotEmpty() } ?: fail("the peer key file $path is empty")
        } catch (_: NoSuchFileException) {
            fail(
                if (envKeyFile == null) {
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

    /** `<settings dir>/peers/<name>/peer.key`, resolved from explicit `--as`, `SPLICE_PEER_NAME`, an ancestor-owned
     *  peer, or `CODEX_THREAD_ID`; null when none identifies a peer. */
    private fun keyFileOf(asOption: String?): Path? {
        val name = asOption
            ?: env(PEER_NAME_ENV)?.takeIf(String::isNotBlank)
            ?: ancestorOwnedPeer()
            ?: env(CODEX_THREAD_ENV)?.let(codexNames::thread)?.let(codexNames::peer)
        return name?.let { settingsDir().resolve("peers").resolve(it).resolve("peer.key") }
    }

    /** The single peer owned by the nearest matching ancestor process in `<settings dir>/peers/`, or null. */
    private fun ancestorOwnedPeer(): String? {
        val owners = PeerStore(settingsDir()).nativeOwners
        if (owners.isEmpty()) return null
        return ownerProcesses.ancestors().firstNotNullOfOrNull { ancestor ->
            owners.entries.filter { it.value == ancestor }.singleOrNull()?.key
        }
    }

    /** `<settings dir>`: the folder holding splice.toml, where `peers/` lives. */
    fun settingsDir(): Path = KeyStorePath.defaultPath(env).toAbsolutePath().parent

    /** A call with no body (GET, DELETE, or a POST that carries nothing). */
    fun call(method: String, path: String, bearer: String, waitMs: Int = CALL_TIMEOUT_MS): PeerAnswer =
        answer(ControlPlaneClient.send(url(path), method, bearer, waitMs))

    /** A call with a JSON body. A connection cut after the body went up may have acted, and says so. */
    fun call(
        method: String,
        path: String,
        bearer: String,
        body: JsonObject,
        waitMs: Int = CALL_TIMEOUT_MS,
    ): PeerAnswer =
        when (val read = ControlPlaneClient.withBody(url(path), method, bearer, body.toString(), waitMs)) {
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
        return PeerAnswer.Exit(PeerExit.refused(reply.status), body)
    }

    private fun url(path: String): String = "http://127.0.0.1:${AdminSupport.controlPort(env)}$path"

    private fun fail(message: String): String? {
        err.line("splice $verb: $message")
        return null
    }
}
