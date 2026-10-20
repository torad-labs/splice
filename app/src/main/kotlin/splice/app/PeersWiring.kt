// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 7, package D): where the daemon builds peer messaging. The store is
// read once at boot from `<settings dir>/peers/`, which is absent until the first `splice peer register`, so a user who
// never registers one gets no file, no socket and no process: only the routes, which answer an empty list.
package splice.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import splice.app.control.mount.ControlGuard
import splice.app.control.mount.PeerParts
import splice.app.control.mount.PeerServiceSource
import splice.app.control.mount.PeerServices
import splice.app.control.mount.PeersMount
import splice.app.peer.PeerPlaces
import splice.app.peer.PeerSupervisor
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.sessions.note.PeerNoteSocket
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.SessionSource
import splice.topology.TopologyLoader
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

internal class PeersWiring(private val env: EnvReader = EnvReader(System::getenv)) {

    /** The registered peers, under the folder holding splice.toml; reading a missing folder creates nothing. A folder
     *  that cannot be read boots with no peers and says so in [log], so no peer key opens a route until it is fixed. */
    fun store(log: LogSink): PeerStore {
        val store = PeerStore(settingsDir())
        store.loadFailure?.let { log("peers: the peers folder could not be read, so no peer key is admitted: $it") }
        return store
    }

    /** The /api/peers routes over [store] and the session registry. [services] is read at each request, so the helper
     *  supervisor and the journal can be wired after the server is built; until then the routes that need them
     *  answer 503. */
    fun mount(
        store: PeerStore,
        sessions: SessionSource,
        guard: ControlGuard,
        log: LogSink,
        services: PeerServiceSource,
    ): PeersMount {
        val outbox = PeerNoteSocket(ProcessDispatchers().io(), registryDirs = registryDirs())
        return PeersMount(PeerParts(store, sessions, outbox, services, log), guard)
    }

    /**
     * The helper supervisor and the journal over the same [store] the routes and the PEER door use. The supervisor
     * adopts the helpers still running, then reconciles every 10 s, in [scope], which the daemon cancels at stop.
     * No store means no peers at all, and nothing is started.
     */
    fun services(store: PeerStore?, stateDir: Path, scope: CoroutineScope, log: LogSink): PeerServices {
        if (store == null) return PeerServices()
        val runtimeDir = env("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val places = PeerPlaces(settingsDir(), stateDir, registryDirs(), runtimeDir)
        val supervisor = PeerSupervisor(store, places, env, ProcessDispatchers().io(), log)
        scope.launch { supervisor.run() }
        return PeerServices(supervisor, PeerMailbox(store, stateDir, log))
    }

    /** Where Claude Code's registry records sit; a send's key file and a receipt's are looked for here. */
    fun registryDirs(): List<Path> = listOf(UserHome.claudeDir(env).resolve("sessions"))

    private fun settingsDir(): Path = TopologyLoader.configPath(env).toAbsolutePath().parent
}
