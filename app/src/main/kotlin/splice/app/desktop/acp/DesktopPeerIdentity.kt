// NEW: desktop-antigravity automatic outbound peer identity (docs/specs/desktop-antigravity.md). When `splice desktop
// acp` launches `agy acp`, it best-effort registers an owner-bound peer pointing at this live `splice desktop acp`
// process so the peer auto-retires on exit, and injects `SPLICE_PEER_NAME` and `SPLICE_PEER_KEY_FILE` into `agy`'s
// environment so `agy` can call `splice peer send <target> "..."` directly without `--as`. When the daemon is not
// running or no management key exists, the session still starts normally with its in-process UDS inbox.
package splice.app.desktop.acp

import splice.app.cli.AdminSupport
import splice.app.cli.peer.PEER_KEY_FILE_ENV
import splice.app.cli.peer.PEER_NAME_ENV
import splice.app.cli.peer.PeerAnswer
import splice.app.cli.peer.PeerAttachNames
import splice.app.cli.peer.PeerCall
import splice.app.cli.peer.PeerRegistrationBody
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.daemonclient.MgmtKeyRead
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.nio.file.Path

// why: nativeInbox registration spawns no peer-host helper, so loopback registration finishes in milliseconds; 1.5 s
// bounds startup latency tightly if the daemon is busy or wedged.
private const val ACP_PEER_REGISTER_TIMEOUT_MS = 1_500
private const val ACP_PEER_PREFIX = "agy"

// why: 8 lowercase characters from the session id distinguish concurrent agy sessions cleanly inside 32 chars.
private const val ACP_SESSION_SEED_CHARS = 8

/** Registers an owner-bound outbound peer for a `splice desktop acp` process and returns child env vars for `agy`. */
internal class DesktopPeerIdentity(
    private val env: EnvReader,
    private val log: LogSink,
    private val processes: PeerOwnerProcesses = PeerOwnerProcesses(),
) {
    /** Best-effort child env variables (`SPLICE_PEER_NAME`, `SPLICE_PEER_KEY_FILE`) for the spawned `agy acp` process. */
    fun childEnv(args: List<String>, cwd: Path, pid: Long = ProcessHandle.current().pid()): Map<String, String> {
        if (!env(PEER_KEY_FILE_ENV).isNullOrBlank()) return emptyMap()
        val mgmtKey = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key
        val owner = if (mgmtKey != null) processes.capture(pid) else null
        return if (mgmtKey != null && owner != null) register(mgmtKey, peerName(args, pid), cwd, owner) else emptyMap()
    }

    private fun register(
        mgmtKey: String,
        name: String,
        cwd: Path,
        owner: splice.sessions.peer.codex.NativeProcess,
    ): Map<String, String> {
        val body = PeerRegistrationBody.buildOwned(name, mapOf("--cwd" to cwd.toString()), owner, nativeInbox = true)
        val call = PeerCall(env, TerminalOutput { log("[antigravity] $it\n") }, ownerProcesses = processes)
        val reply = (call.call("POST", "/api/peers", mgmtKey, body, ACP_PEER_REGISTER_TIMEOUT_MS) as? PeerAnswer.Ok)
            ?.body
        val registeredName = reply?.let { JsonScalars.str(it, "name") }
        val keyFile = reply?.let { JsonScalars.str(it, "keyFile") }
        return if (registeredName != null && keyFile != null) {
            mapOf(PEER_NAME_ENV to registeredName, PEER_KEY_FILE_ENV to keyFile)
        } else {
            emptyMap()
        }
    }

    private fun peerName(args: List<String>, pid: Long): String {
        val explicit = env(PEER_NAME_ENV)?.takeIf(String::isNotBlank)
        if (explicit != null) return explicit
        val seed = flagValue(args, "--session-id")
            ?: flagValue(args, "--resume")
        val suffix = seed?.let(PeerAttachNames::sanitize)?.take(ACP_SESSION_SEED_CHARS)?.takeIf(String::isNotEmpty)
            ?: pid.toString()
        return PeerAttachNames.build(ACP_PEER_PREFIX, suffix)
    }

    private fun flagValue(args: List<String>, flag: String): String? {
        var found: String? = null
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg.startsWith("$flag=") -> found = arg.substringAfter('=').takeIf(String::isNotBlank)
                arg == flag && i + 1 < args.size -> {
                    found = args[i + 1].takeIf(String::isNotBlank)
                    i += 1
                }
            }
            i += 1
        }
        return found
    }
}
