// NEW: server mode (docs/specs/server-mode.md, F1). One daemon's server mode, as the daemon holds it: the keys and the
// people it keeps under `<state>/server`, the operations each door asks of them, and the listener that answers key
// holders. The daemon builds it when splice.toml has a `[server]`, opens it once its heads are up, and closes it first
// at stop.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import splice.core.config.TurnKey
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.share.RefusalVoice
import splice.share.RelaySpeaker
import splice.share.ShareRelay
import splice.share.SharedHead
import splice.share.SharedHeads
import java.nio.file.Path
import java.time.Clock

/** Of the heads this machine can offer, the ones `[server] commands` names, by command or by head key. */
internal class ServedHeads(private val all: SharedHeads, private val commands: List<String>) : SharedHeads {
    override val machine: String get() = all.machine

    override fun offered(): List<SharedHead> = all.offered().filter { it.command in commands || it.key in commands }
}

/** How the listener reaches this machine's heads: the turn key they admit, and the control port beside them. */
public data class ServerReach(val turnKey: TurnKey, val controlPort: Int)

public class ServerMode(
    private val server: ServerConfig,
    /** `<state>/server`, where the keys and the people are kept. A file there that cannot be read throws here. */
    folder: Path,
    /** Blocking work runs here: the two files' writes, a head's launch, the relay's I/O. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    /** What time it is, for a key that expires and for the minute a person is counted over. */
    private val clock: Clock = Clock.systemUTC(),
) {
    private val keys = ServerKeys(folder.resolve(KEYS_FILE), io, clock)
    private val people = ServerPeople(folder.resolve(PEOPLE_FILE), io)

    /** The keys' operations, as the CLI and the control plane ask them. */
    public val keyApi: KeyApi = KeyApi(keys, log)

    /** Each person's settings, as the CLI and the control plane set them. */
    public val personApi: PersonApi = PersonApi(people, log)

    @Volatile
    private var listener: ServerListener? = null

    /** Opens the listener on [address], serving the heads of [heads] that `[server] commands` names. [configDir] is
     *  splice.toml's directory, which a relative PEM path is under. Throws when the address cannot be bound or the
     *  PEM files cannot be read. */
    public fun open(address: ServerAddress, heads: SharedHeads, reach: ServerReach, configDir: Path) {
        val speaker = RelaySpeaker(RefusalVoice(SERVER_ERROR_PREFIX), SERVER_LOG_TAG)
        val parts = ServerParts(
            keys = keys,
            limits = PersonLimits(server.limits, people, clock),
            heads = ServedHeads(heads, server.commands),
            relay = ShareRelay(reach.turnKey, reach.controlPort, heads.machine, io, log, speaker),
            tls = server.pem(configDir)?.let { PemTls(it).keyStore() },
        )
        val opened = ServerListener(server, parts, io, log)
        opened.start(address)
        listener = opened
    }

    /** Closes the listener and writes when each key was last used, so a restart loses none of it. */
    public suspend fun close() {
        listener?.stop()
        listener = null
        keys.keepUse(olderThanMs = 0)
    }
}

private const val KEYS_FILE = "keys.json"
private const val PEOPLE_FILE = "people.json"
private const val SERVER_LOG_TAG = "server"
