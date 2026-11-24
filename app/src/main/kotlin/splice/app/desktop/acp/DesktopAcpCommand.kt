// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). `splice desktop acp [claude args...]`: what
// Claude Desktop's launcher runs in place of `claude` for a `claude-antigravity--` model, through the `claude` shim
// install.sh lays in `~/.local/lib/splice/gemini/`. It runs the session in this folder on `agy acp` (DesktopAcp),
// speaking Desktop's stream-json on this process's own standard input and output, so nothing else may write to
// standard output; what goes wrong outside the conversation goes to standard error, which Desktop logs. It needs no
// daemon.
package splice.app.desktop.acp

import splice.app.cli.peer.PEER_NAME_ENV
import splice.core.config.KeyStorePath
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.provider.antigravity.DesktopAcp
import splice.provider.antigravity.peer.DesktopPeerWiring
import splice.topology.TopologyStatePaths
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

internal class DesktopAcpCommand(private val env: EnvReader, private val err: TerminalOutput) {
    /** Runs the session Desktop started with `claude` [args]; its exit code. */
    suspend fun run(args: List<String>): Int {
        val log = LogSink { err.line(it.trimEnd()) }
        val cwd = Path.of("").toAbsolutePath()
        val io = ProcessDispatchers().io()
        val childEnv = DesktopPeerIdentity(env, log).childEnv(args, cwd)
        val peerName = childEnv[PEER_NAME_ENV] ?: env(PEER_NAME_ENV)?.takeIf(String::isNotBlank)
        val receiptStore = DesktopPeerReceiptStore(
            stateDir = TopologyStatePaths(env).current().stateDir,
            settingsDir = KeyStorePath.defaultPath(env).toAbsolutePath().parent,
            peerName = peerName,
        )
        val socketConfig = DesktopPeerSocketConfig(receiptStore = receiptStore)
        val peer = DesktopPeerWiring(
            inbox = DesktopPeerSocketFactory(env, io, log, config = socketConfig),
            childEnv = childEnv,
        )
        val session = DesktopAcp(args, cwd, env, io, log)
        return session.run(System.`in`, System.out, peer)
    }
}
