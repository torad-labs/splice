// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). `splice desktop acp [claude args...]`: what
// Claude Desktop's launcher runs in place of `claude` for a `claude-antigravity--` model, through the `claude` shim
// install.sh lays in `~/.local/lib/splice/gemini/`. It runs the session in this folder on `agy acp` (DesktopAcp),
// speaking Desktop's stream-json on this process's own standard input and output, so nothing else may write to
// standard output; what goes wrong outside the conversation goes to standard error, which Desktop logs. It needs no
// daemon.
package splice.app.desktop.acp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import splice.app.cli.peer.PEER_NAME_ENV
import splice.app.cli.peer.PeerMcpLaunch
import splice.app.control.peer.PEER_MCP_SERVER
import splice.core.config.KeyStorePath
import splice.core.harness.HarnessCatalog
import splice.core.harness.HarnessDefinition
import splice.core.harness.HarnessPeerProtocol
import splice.core.mcp.McpHarness
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.provider.antigravity.DesktopAcp
import splice.provider.antigravity.peer.DesktopPeerWiring
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopAcpCommand(private val env: EnvReader, private val err: TerminalOutput) {
    /** Runs the session Desktop started with `claude` [args]; its exit code. */
    suspend fun run(args: List<String>): Int {
        val launch = selection(args) ?: return 2
        val log = LogSink { err.line(it.trimEnd()) }
        val cwd = Path.of("").toAbsolutePath()
        val io = ProcessDispatchers().io()
        val hookScope = LifecycleScope(io)
        return try {
            val hooks = DesktopAcpHooks(env, hookScope, log).build()
            val session = launch.command?.let { DesktopAcp(launch.args, cwd, env, io, log, agy = it) }
                ?: DesktopAcp(launch.args, cwd, env, io, log)
            session.run(System.`in`, System.out, hooks, peerWiring(launch, cwd, io, log))
        } finally {
            hookScope.cancel()
        }
    }

    private data class AcpLaunch(
        val args: List<String>,
        val harness: HarnessDefinition,
        val command: List<String>? = null,
    )

    private fun selection(args: List<String>): AcpLaunch? {
        if (args.firstOrNull() != "--harness") return AcpLaunch(args, McpHarness.AGY.definition)
        return localSelection(args)
    }

    private fun localSelection(args: List<String>): AcpLaunch? {
        val catalog = readCatalog() ?: return null
        val id = args.getOrNull(1)
        val harness = catalog.entries.firstOrNull { it.wire == id }
        val command = id?.let(catalog::acpCommand)
        return if (harness == null || command == null) {
            err.line("splice desktop acp: --harness requires a locally declared ACP harness id")
            null
        } else {
            AcpLaunch(args.drop(2), harness, command)
        }
    }

    private fun readCatalog(): HarnessCatalog? {
        val path = TopologyLoader.configPath(env).toAbsolutePath()
        val read = try {
            ConfigFindings.read(Files.readString(path), path.parent, path.parent)
        } catch (_: IOException) {
            err.line("splice desktop acp: cannot read the harness catalog")
            return null
        }
        return when (read) {
            is ConfigRead.Ready -> read.topology.harnessCatalog
            is ConfigRead.Refused -> {
                read.findings.forEach { err.line(it.text()) }
                null
            }
        }
    }

    private fun peerWiring(
        launch: AcpLaunch,
        cwd: Path,
        io: CoroutineDispatcher,
        log: LogSink,
    ): DesktopPeerWiring {
        val peer = launch.harness.peer
        val childEnv = if (peer?.autoSubscribe == true) {
            DesktopPeerIdentity(env, log, harness = launch.harness.wire).childEnv(launch.args, cwd)
        } else {
            emptyMap()
        }
        val mcp = buildJsonObject {
            if (peer?.autoSubscribe == true) put(PEER_MCP_SERVER, PeerMcpLaunch(env).entry())
        }
        val inbox = peer?.driver?.takeIf { it == HarnessPeerProtocol.ACP }
            ?.let { inboxFactory(childEnv[PEER_NAME_ENV], io, log) }
        return DesktopPeerWiring(inbox, childEnv, mcp, forwardLaunchOptions = launch.command == null)
    }

    private fun inboxFactory(peerName: String?, io: CoroutineDispatcher, log: LogSink): DesktopPeerSocketFactory {
        val receiptStore = DesktopPeerReceiptStore(
            stateDir = TopologyStatePaths(env).current().stateDir,
            settingsDir = KeyStorePath.defaultPath(env).toAbsolutePath().parent,
            peerName = peerName,
        )
        val socketConfig = DesktopPeerSocketConfig(receiptStore = receiptStore)
        return DesktopPeerSocketFactory(env, io, log, config = socketConfig)
    }
}
