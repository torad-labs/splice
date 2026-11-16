// NEW: server mode (docs/specs/server-mode.md, F1). The daemon's part of it: when splice.toml has a `[server]`, the
// keys and the people kept under `<state>/server` and the listener that answers key holders, opened once the heads
// are up and closed first at stop. A server mode that can't start (a port another program holds, a PEM file that is
// no certificate, a keys file that cannot be read) is said in the log and to every `splice server` verb, and leaves
// the rest of the daemon serving. It is also how server mode reads the servers behind a head (F4): the pool of the
// provider that head's splice.toml table names, the caller's headers that provider's `forward_headers` names, and
// that provider itself when it speaks openai-chat, which is where an OpenAI-style client's requests are relayed
// (DaemonChatServer).
package splice.app.server

import splice.app.control.ManagedHead
import splice.app.control.ServerModeSource
import splice.app.share.ManagedSharedHeads
import splice.core.config.ConfigService
import splice.core.config.TurnKey
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.pool.UpstreamPool
import splice.core.server.ServedTokens
import splice.core.server.ServedTurnBoard
import splice.core.topology.Dialect
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
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

    // The heads the server serves, once it opened: what an OpenAI-style turn is priced at is its head's rate card.
    @Volatile
    private var heads: Map<String, ManagedHead> = emptyMap()

    override fun mode(): ServerMode? = running

    override fun absent(): String = down

    override fun all(): List<UpstreamPool> = pools.all()

    override fun of(head: String): UpstreamPool? = topology.heads[head]?.let { pools.named(it.provider) }

    override fun chat(head: String): ChatServer? {
        val key = topology.heads[head]?.provider ?: return null
        val provider = topology.providers[key]?.takeIf { it.dialect == Dialect.OPENAI_CHAT } ?: return null
        return DaemonChatServer(key, provider, pools)
    }

    override fun forwarded(head: String): List<String> =
        topology.heads[head]?.let { topology.providers[it.provider] }?.forwarded?.carried.orEmpty()

    override fun price(head: String, model: String, tokens: ServedTokens): Double? {
        val catalog = heads[head]?.statusline?.catalog ?: return null
        val billed = mapOf(
            PerfKeys.IN_TOKENS to tokens.prompt,
            PerfKeys.CACHED_TOKENS to tokens.cached,
            PerfKeys.OUT_TOKENS to tokens.completion,
            PerfKeys.CACHE_WRITE_TOKENS to 0L,
        )
        return TurnPrice(catalog).usd(model, billed)
    }

    /** Opens server mode when `[server]` names an address, serving [heads] through [controlPort]'s daemon, which say
     *  how each turn went on [served]. */
    fun open(heads: Map<String, ManagedHead>, controlPort: Int, served: ServedTurnBoard) {
        val address = topology.server.address ?: return
        this.heads = heads
        Cancellables.runCatchingBestEffort {
            val offered = ManagedSharedHeads(heads, topology, config, ShareMachine(log).name())
            val folder = stateDir.resolve(SERVER_STATE)
            val reach = ServerReach(turnKey, controlPort, served, EnvReader(System::getenv))
            ServerMode(topology.server, folder, ProcessDispatchers().io(), log, upstreams = this)
                .also { it.open(address, offered, reach, configDir) }
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
