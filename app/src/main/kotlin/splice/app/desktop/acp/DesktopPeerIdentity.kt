// PID-bound peer registration for Desktop-launched harnesses. The key stays in its owner-only file.
package splice.app.desktop.acp

import splice.app.cli.AdminSupport
import splice.app.cli.peer.PEER_KEY_FILE_ENV
import splice.app.cli.peer.PEER_NAME_ENV
import splice.app.cli.peer.PeerAnswer
import splice.app.cli.peer.PeerAttachNames
import splice.app.cli.peer.PeerCall
import splice.app.cli.peer.PeerRegistrationBody
import splice.core.mcp.McpHarness
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

// why: leave space for the PID as well as the harness name in the 32-character peer name.
private const val ACP_HARNESS_NAME_CHARS = 16

// why: 8 lowercase characters from the session id distinguish concurrent agy sessions cleanly inside 32 chars.
private const val ACP_SESSION_SEED_CHARS = 8

/** Registers an owner-bound outbound peer for a `splice desktop acp` process and returns child env vars for `agy`. */
internal class DesktopPeerIdentity(
    private val env: EnvReader,
    private val log: LogSink,
    private val processes: PeerOwnerProcesses = PeerOwnerProcesses(),
    private val harness: String = McpHarness.AGY.definition.wire,
) {
    /** Best-effort child env variables (`SPLICE_PEER_NAME`, `SPLICE_PEER_KEY_FILE`) for the spawned `agy acp` process. */
    fun childEnv(args: List<String>, cwd: Path, pid: Long = ProcessHandle.current().pid()): Map<String, String> {
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
        val native = harness == McpHarness.AGY.definition.wire || harness == McpHarness.CLAUDE.definition.wire
        val body = PeerRegistrationBody.buildOwned(
            name,
            mapOf("--cwd" to cwd.toString()),
            owner,
            nativeInbox = native,
            harness = harness,
        )
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
        if (harness == McpHarness.CLAUDE.definition.wire) return "cc-$pid"
        val explicit = env(PEER_NAME_ENV)?.takeIf(String::isNotBlank)
            ?.takeIf { env(PEER_KEY_FILE_ENV).isNullOrBlank() }
        if (explicit != null) return explicit
        val seed = flagValue(args, "--session-id")
            ?: flagValue(args, "--resume")
        val suffix = seed?.let(PeerAttachNames::sanitize)?.take(ACP_SESSION_SEED_CHARS)?.takeIf(String::isNotEmpty)
            ?: pid.toString()
        val prefix = if (harness == McpHarness.AGY.definition.wire) {
            ACP_PEER_PREFIX
        } else {
            harness.take(ACP_HARNESS_NAME_CHARS)
        }
        return PeerAttachNames.build(prefix, suffix)
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
