// NEW: server mode (docs/specs/server-mode.md, F1). The daemon's part of it: when splice.toml has a `[server]`, the
// keys and the people kept under `<state>/server` and the listener that answers key holders, opened once the heads
// are up and closed first at stop. A server mode that can't start (a port another program holds, a PEM file that is
// no certificate, a keys file that cannot be read) is said in the log and to every `splice server` verb, and leaves
// the rest of the daemon serving.
package splice.app.server

import splice.app.control.ManagedHead
import splice.app.control.ServerModeSource
import splice.app.share.ManagedSharedHeads
import splice.core.config.ConfigService
import splice.core.config.TurnKey
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.server.ServerMode
import splice.server.ServerReach
import splice.share.ShareMachine
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

internal class DaemonServer(
    private val topology: Topology,
    private val config: ConfigService,
    private val turnKey: TurnKey,
    private val log: LogSink,
    /** splice's state folder; the server keeps its keys and its people under `server/` in it. */
    private val stateDir: Path,
    /** splice.toml's directory, which a relative `tls_cert` or `tls_key` is under. */
    private val configDir: Path,
) : ServerModeSource {
    @Volatile
    private var running: ServerMode? = null

    @Volatile
    private var down: String = NO_SERVER

    override fun mode(): ServerMode? = running

    override fun absent(): String = down

    /** Opens server mode when `[server]` names an address, serving [heads] through [controlPort]'s daemon. */
    fun open(heads: Map<String, ManagedHead>, controlPort: Int) {
        val address = topology.server.address ?: return
        Cancellables.runCatchingBestEffort {
            val offered = ManagedSharedHeads(heads, topology, config, ShareMachine(log).name())
            ServerMode(topology.server, stateDir.resolve(SERVER_STATE), ProcessDispatchers().io(), log)
                .also { it.open(address, offered, ServerReach(turnKey, controlPort), configDir) }
        }.onSuccess { running = it }.onFailure { failure ->
            val why = SafeFailureText.render(failure)
            down = "server mode did not start on ${topology.server.listen}: $why"
            log("[server] not serving on ${topology.server.listen}: $why\n")
        }
    }

    suspend fun close() {
        running?.close()
        running = null
    }
}

// The server's folder under splice's state.
private const val SERVER_STATE = "server"

private const val NO_SERVER = "this daemon runs no server: splice.toml has no [server]"
