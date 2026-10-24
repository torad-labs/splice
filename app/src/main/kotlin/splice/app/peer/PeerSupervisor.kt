// NEW: peer messaging, package B (docs/specs/peer-messaging.md 4.1) — the daemon's side of the peer helpers. At daemon
// start it adopts each registered peer's helper that still runs this build, restarts one that runs another build, and
// starts one where none runs. Every 10 s it reconciles: a dead helper restarts after min(60 s, 2^(n-1) s), counted from
// its last start. It starts nothing, reads nothing and writes nothing while no peer is registered.
//
// A helper outlives the daemon, which restarts many times a day. The spawn is HubHolders' shape: under a systemd unit
// it runs in its own `systemd-run --user --scope`, because a process left in splice.service's cgroup is killed with the
// unit (KillMode=mixed); elsewhere it runs under setsid. Every standard stream is discarded. A helper is signalled only
// while its helper.json pid runs with the procStart it recorded, so a reused pid is never hit, and a leftover registry
// record, key file and socket are removed only when the record is this peer's or gone. Revoke deletes the peer's folder
// before it stops the helper, so the supervisor keeps each peer's session id from the last time it saw peer.json; a
// dead helper's leftovers are cleared before its restart, so a later revoke never meets a socket no helper.json names.
package splice.app.peer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import splice.app.control.peer.PeerHelpers
import splice.app.control.peer.RunningHelper
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.peer.helper.HelperFile
import splice.sessions.peer.helper.HelperFiles
import splice.sessions.peer.helper.PeerRegistryFiles
import splice.sessions.peer.helper.PeerStateFiles
import splice.sessions.peer.store.PeerStore
import splice.upstream.Ticker
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessTicker
import splice.upstream.codemode.ProcessWaiter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// why: the spec's reconcile period (4.1); a dead peer is back within one period plus its backoff.
private const val RECONCILE_MS = 10_000L

// why: the backoff ceiling the spec names: min(60 s, 2^(n-1) s).
private const val MAX_BACKOFF_MS = 60_000L

// why: 2^6 s already passes the 60 s ceiling; a larger shift only risks overflow.
private const val MAX_SHIFT = 6

// why: a JVM takes a few seconds to bind and write helper.json; a start younger than this is still starting, not dead.
private const val HELPER_START_GRACE_MS = 15_000L

// why: a helper alive this long after its start is healthy, and its next death counts from one again.
private const val STABLE_MS = 60_000L

// why: SIGTERM, then SIGKILL after 5 s (spec 3.2 revoke); the helper needs only to finish the line in hand.
private const val TERM_WAIT_MS = 5_000L

// why: a SIGKILL ends a process at once; this only bounds the wait for the kernel to reap it.
private const val KILL_WAIT_MS = 2_000L

// why: register answers once the helper is on the registry, waiting at most 10 s (spec 3.2), then answers with no pid.
private const val REGISTER_WAIT_MS = 10_000L

// why: a JVM start takes seconds; looking five times a second answers within a fifth of a second of the record.
private const val REGISTER_POLL_MS = 200L

/** One peer's restart history: how many starts in a row died, and when the last one began. */
private data class Restarts(val failures: Int, val lastStart: Long)

/** The daemon's supervisor of peer helpers, as the /api/peers routes use it ([PeerHelpers]). */
internal class PeerSupervisor(
    private val store: PeerStore,
    private val places: PeerPlaces,
    env: EnvReader,
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    private val waiter: Waiter = ProcessWaiter(),
) : PeerHelpers {
    private val clock = WallClock { System.currentTimeMillis() }
    private val launcher = HelperLaunch(places, env)
    private val helpers = HelperFiles()
    private val build = PeerBuild()
    private val restarts = ConcurrentHashMap<String, Restarts>()

    /** Each peer's session id as last read from peer.json, for the leftovers of a peer whose folder is gone. */
    private val sessions = ConcurrentHashMap<String, String>()

    /** Peers being revoked: never restarted until they are registered again. */
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Adopts what runs, then reconciles every 10 s until the daemon stops. */
    suspend fun run(ticker: Ticker = ProcessTicker()) {
        adopt()
        while (ticker.awaitTick(RECONCILE_MS)) reconcile()
    }

    /** Starts [name]'s helper unless one runs, and waits at most 10 s for it to register: its pid, or null while it
     *  is still starting. A peer registered again is no longer held. */
    override suspend fun start(name: String): Long? {
        held.remove(name)
        store.get(name)?.let { sessions[name] = it.sessionId }
        if (running(name) == null) launch(name, failures = 0)
        val deadline = clock() + REGISTER_WAIT_MS
        var helper = running(name)
        while (helper == null && clock() < deadline) {
            waiter.wait(REGISTER_POLL_MS)
            helper = running(name)
        }
        return helper?.pid
    }

    /** Stops [name]'s helper (SIGTERM, then SIGKILL after 5 s) and removes what it left behind, the record and key
     *  file only when they are this peer's. Works on a helper that is already dead. */
    override suspend fun stop(name: String) {
        held.add(name)
        restarts.remove(name)
        val files = PeerStateFiles(places.stateDir, name)
        val sessionId = store.get(name)?.sessionId ?: sessions[name]
        sessions.remove(name)
        val helper = helpers.read(files) ?: return
        if (helpers.alive(helper)) terminate(helper.pid)
        if (helpers.alive(helper)) {
            say("$name: helper ${helper.pid} did not exit")
        } else {
            leftovers(name, helper, files, sessionId)
        }
    }

    /** Asks [name]'s helper to re-read peer.json and the webhook secret (SIGHUP); nothing when no helper runs. */
    override fun reload(name: String) {
        val helper = running(name) ?: return
        val sent = Cancellables.runCatchingCancellable {
            ProcessBuilder("kill", "-HUP", helper.pid.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        sent.exceptionOrNull()?.let { say("$name: reload not sent (${SafeFailureText.render(it)})") }
    }

    override fun running(name: String): RunningHelper? =
        helpers.read(PeerStateFiles(places.stateDir, name))?.takeIf(helpers::alive)
            ?.let { RunningHelper(it.pid, it.socket) }

    /** At daemon start: a helper on this build is kept, one on another build restarted, a dead one started. */
    private suspend fun adopt() {
        val current = build.current()
        for (peer in store.list()) {
            sessions[peer.name] = peer.sessionId
            val files = PeerStateFiles(places.stateDir, peer.name)
            val helper = helpers.read(files)
            val alive = helper != null && helpers.alive(helper)
            val sameBuild = helper?.version == current
            if (alive && !sameBuild) {
                stop(peer.name)
                held.remove(peer.name)
                sessions[peer.name] = peer.sessionId
            }
            if (helper != null && !alive) leftovers(peer.name, helper, files, peer.sessionId)
            if (!alive || !sameBuild) launch(peer.name, failures = 0)
        }
    }

    private fun reconcile() {
        held.retainAll { store.get(it) != null }
        for (peer in store.list()) {
            sessions[peer.name] = peer.sessionId
            if (peer.name !in held) reconcile(peer.name, peer.sessionId)
        }
    }

    private fun reconcile(name: String, sessionId: String) {
        val files = PeerStateFiles(places.stateDir, name)
        val helper = helpers.read(files)
        val now = clock()
        if (helper != null && helpers.alive(helper)) {
            if (now - helper.startedAt > STABLE_MS) restarts.remove(name)
            return
        }
        val last = restarts[name]
        if (last != null && now - last.lastStart < HELPER_START_GRACE_MS + backoff(last.failures)) return
        helper?.let { leftovers(name, it, files, sessionId) }
        launch(name, failures = (last?.failures ?: 0) + 1)
    }

    private fun launch(name: String, failures: Int) {
        restarts[name] = Restarts(failures, clock())
        val refused = launcher.launch(name)
        when {
            refused != null -> say("$name: helper not started ($refused)")
            failures > 0 -> say("$name: helper restarting, attempt $failures")
            else -> say("$name: helper starting")
        }
    }

    private fun backoff(failures: Int): Long =
        if (failures <= 0) 0L else minOf(MAX_BACKOFF_MS, 1_000L shl minOf(failures - 1, MAX_SHIFT))

    private suspend fun terminate(pid: Long) {
        val process = ProcessHandle.of(pid).orElse(null) ?: return
        process.destroy()
        if (exited(process, TERM_WAIT_MS)) return
        process.destroyForcibly()
        exited(process, KILL_WAIT_MS)
    }

    private suspend fun exited(process: ProcessHandle, waitMs: Long): Boolean = runInterruptible(io) {
        try {
            process.onExit().get(waitMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: TimeoutException) {
            !process.isAlive
        }
    }

    /** The record, key file, socket and helper.json a dead helper left behind; [sessionId] tells its record. */
    private fun leftovers(name: String, helper: HelperFile, files: PeerStateFiles, sessionId: String?) {
        val cleaned = Cancellables.runCatchingCancellable {
            val ours = sessionId != null &&
                PeerRegistryFiles(places.registryDirs).withdraw(helper.pid, Path.of(helper.socket), sessionId)
            // A socket path is the pid's; when another process holds that pid's record, the socket is its too.
            if (ours) Files.deleteIfExists(Path.of(helper.socket))
            // A helper started since this one was read has written its own helper.json, which stays.
            if (helpers.read(files)?.pid == helper.pid) Files.deleteIfExists(files.helperJson)
        }
        cleaned.exceptionOrNull()?.let { say("$name: leftovers stay (${SafeFailureText.render(it)})") }
    }

    private fun say(line: String) {
        log("[peers] ${LogSafe.str(line)}\n")
    }
}
