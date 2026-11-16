// NEW: server mode (docs/specs/server-mode.md, F1). The daemon's part of it: when splice.toml has a `[server]`, the
// keys and the people kept under `<state>/server` and the listener that answers key holders, opened once the heads
// are up and closed first at stop. A server mode that can't start (a port another program holds, a PEM file that is
// no certificate, a keys file that cannot be read) is said in the log and to every `splice server` verb, and leaves
// the rest of the daemon serving. It is also how server mode reads the servers behind a head (F4): the pool of the
// provider that head's splice.toml table names, and that provider itself when it speaks openai-chat, which is where
// an OpenAI-style client's requests are relayed (DaemonChatServer).
package splice.app.server

import splice.app.control.ManagedHead
import splice.app.control.ServerModeSource
import splice.app.share.ManagedSharedHeads
import splice.core.config.ConfigService
import splice.core.config.TurnKey
import splice.core.pool.UpstreamPool
import splice.core.topology.Dialect
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.server.ChatServer
import splice.server.HeadUpstreams
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
    /** The pools of the providers that list upstreams (F4), as the heads' providers hold them. */
    private val pools: UpstreamPools,
) : ServerModeSource, HeadUpstreams {
    @Volatile
    private var running: ServerMode? = null

    @Volatile
    private var down: String = NO_SERVER

    override fun mode(): ServerMode? = running

    override fun absent(): String = down

    override fun all(): List<UpstreamPool> = pools.all()

    override fun of(head: String): UpstreamPool? = topology.heads[head]?.let { pools.named(it.provider) }

    override fun chat(head: String): ChatServer? {
        val key = topology.heads[head]?.provider ?: return null
        val provider = topology.providers[key]?.takeIf { it.dialect == Dialect.OPENAI_CHAT } ?: return null
        return DaemonChatServer(key, provider, pools)
    }

    /** Opens server mode when `[server]` names an address, serving [heads] through [controlPort]'s daemon. */
    fun open(heads: Map<String, ManagedHead>, controlPort: Int) {
        val address = topology.server.address ?: return
        Cancellables.runCatchingBestEffort {
            val offered = ManagedSharedHeads(heads, topology, config, ShareMachine(log).name())
            val folder = stateDir.resolve(SERVER_STATE)
            ServerMode(topology.server, folder, ProcessDispatchers().io(), log, upstreams = this)
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
