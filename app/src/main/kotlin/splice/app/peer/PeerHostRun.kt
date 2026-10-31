// NEW: Oct 28, 2026 (Marcos: "yeah replcace the java process per peer"; docs/specs/peer-messaging.md 4.1) — the one
// process that serves every peer of a state folder, where each peer ran a JVM of its own:
// `java -Xmx128m -XX:+UseSerialGC -cp <splice.jar> splice.app.MainKt peer-host --settings DIR --state DIR
// --registry DIR... [--runtime DIR] --build ID`, started by PeerSupervisor's HelperLaunch. Main.kt hands it here,
// since LayerMapLawTest keeps Main.kt the one process entry in :app. The daemon passes every folder, so the host reads
// no environment and sees exactly the daemon's view. It holds host.lock, writes host.json, serves each `start/<name>`
// asked of its build (PeerHost.kt), runs every peer's look at peer.json on a SIGHUP, and exits 10 s after its last peer
// ends. SIGTERM and SIGINT end it through the shutdown hook, which only asks main to stop and waits while main stops
// every peer: each removes its record, key file, socket and helper.json, and its anchor process ends.
package splice.app.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.sessions.peer.helper.HelperFiles
import splice.sessions.peer.helper.HelperSetup
import splice.sessions.peer.helper.PeerSockets
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessWaiter
import sun.misc.Signal
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// why: EX_USAGE from sysexits.h; only a daemon that spelled the command line wrong sees it.
private const val HOST_USAGE_EXIT = 64

// why: the stop ends every peer, each removing four files and finishing the line in hand; past this the JVM ends.
private const val STOP_WAIT_MS = 15_000L

// why: a host of an older build that is making way finishes its stop within this; past it this one leaves.
private const val HOST_LOCK_WAIT_MS = 20_000L

// why: how soon a peer asked of the host starts; a listing of one small folder four times a second costs nothing.
private const val POLL_MS = 250L

// why: a peer revoked and registered again, or restarted, finds the host still up within this; past it a host with
// no peer is gone, so no process runs while no peer is registered (docs/peers.md).
private const val IDLE_MS = 10_000L

/** The flags HelperLaunch writes and HelperArgs reads. */
internal const val PEER_SETTINGS_FLAG = "--settings"
internal const val PEER_STATE_FLAG = "--state"
internal const val PEER_BUILD_FLAG = "--build"

/** The host's command line, as HelperLaunch writes it, and each peer's setup from it. */
internal class HelperArgs(private val args: List<String>) {
    fun setup(name: String): HelperSetup? {
        val flags = listOf(PEER_SETTINGS_FLAG, PEER_STATE_FLAG, PEER_BUILD_FLAG).associateWith(::one)
        val registries = all("--registry")
        if (registries.isEmpty() || flags.containsValue(null)) return null
        val sockets = PeerSockets(one("--runtime")?.let(Path::of), uid(), System.getProperty("user.name").orEmpty())
        val settings = Path.of(flags[PEER_SETTINGS_FLAG].orEmpty())
        val state = Path.of(flags[PEER_STATE_FLAG].orEmpty())
        return HelperSetup(name, settings, state, registries.map(Path::of), sockets, flags[PEER_BUILD_FLAG].orEmpty())
    }

    private fun one(flag: String): String? = all(flag).lastOrNull()

    private fun all(flag: String): List<String> =
        args.indices.filter { args[it] == flag && it + 1 < args.size }.map { args[it + 1] }

    /** This process's uid, read off /proc/self, which the kernel makes the process's own. */
    private fun uid(): Long? = Cancellables.runCatchingCancellable {
        (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as? Int)?.toLong()
    }.fold(onSuccess = { it }, onFailure = { null })
}

/** host.lock, held for as long as this host runs. */
internal class HostLock(private val channel: FileChannel, private val lock: FileLock) {
    fun release() {
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                lock.release()
                channel.close()
            },
            "the host is ending, and its exit releases the lock regardless",
        )
    }
}

internal class PeerHostRun(private val args: List<String>) {
    private val dispatchers = ProcessDispatchers()
    private val clock = WallClock { System.currentTimeMillis() }
    private val waiter = ProcessWaiter()

    suspend fun run(): Int {
        val host = HelperArgs(args)
        val build = host.setup(PEER_HOST_COMMAND) ?: return HOST_USAGE_EXIT
        val files = PeerHostFiles(build.stateDir)
        val lock = lock(files) ?: return 0
        val stop = CompletableDeferred<Unit>()
        val stopped = CountDownLatch(1)
        // The hook never stops anything: it asks main to, and waits on a plain latch until main's cleanup is done.
        Runtime.getRuntime().addShutdownHook(
            Executors.defaultThreadFactory().newThread {
                stop.complete(Unit)
                stopped.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
            },
        )
        val scope = LifecycleScope(dispatchers.background())
        val peers = PeerHost(host, scope, clock)
        val self = HelperFiles().self(clock())
        try {
            files.write(HostFile(self.pid, self.procStart, self.startedAt, build.version))
            hangups(peers)
            serve(peers, files, build.version, stop)
        } finally {
            withContext(NonCancellable) {
                peers.close()
                if (files.host()?.pid == self.pid) Files.deleteIfExists(files.hostJson)
                lock.release()
                scope.coroutineContext[Job]?.cancelAndJoin()
                stopped.countDown()
            }
        }
        return 0
    }

    /** Serves each peer asked of [build] until a stop, or until no peer is left or asked for [IDLE_MS]. */
    private suspend fun serve(peers: PeerHost, files: PeerHostFiles, build: String, stop: CompletableDeferred<Unit>) {
        var idleSince = clock()
        while (!stop.isCompleted) {
            for (name in files.take(build)) peers.start(name)
            if (peers.count() > 0 || files.waiting(build)) idleSince = clock()
            if (clock() - idleSince >= IDLE_MS) return
            withTimeoutOrNull(POLL_MS) { stop.await() }
        }
    }

    /** host.lock, waiting while a host of an older build makes way; null when another host keeps it. */
    private suspend fun lock(files: PeerHostFiles): HostLock? {
        if (SecureFile.ownerOnlyDirectory(files.dir) != null) return null
        val mode = SecureFile.CREATE_0600 ?: return null
        val channel = FileChannel.open(files.lock, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE), mode)
        val deadline = clock() + HOST_LOCK_WAIT_MS
        var held = channel.tryLock()
        while (held == null && clock() < deadline) {
            waiter.wait(POLL_MS)
            held = channel.tryLock()
        }
        if (held == null) channel.close()
        return held?.let { HostLock(channel, it) }
    }

    /** SIGHUP is the supervisor's reload: every peer looks at its peer.json now. Its 10 s look is the fallback. */
    private fun hangups(peers: PeerHost) {
        Cancellables.discard(
            Cancellables.runCatchingCancellable { Signal.handle(Signal("HUP")) { peers.look() } },
            "each peer still looks at its peer.json every 10 s",
        )
    }
}
