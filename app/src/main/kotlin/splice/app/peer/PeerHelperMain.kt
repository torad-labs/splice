// NEW: peer messaging, package B (docs/specs/peer-messaging.md 4.1 and 4.2) — the helper process, one per registered
// peer: `java -Xmx48m -XX:+UseSerialGC -cp <splice.jar> splice.app.peer.PeerHelperMain <name> --settings DIR --state DIR
// --registry DIR... [--runtime DIR] --build ID`, started by PeerSupervisor. The daemon passes every folder, so a helper
// reads no environment and sees exactly the daemon's view. It starts as PeerHelper says, then serves its inbox socket,
// rewrites its registry record every 30 s, and looks at peer.json every 10 s: gone means revoked, so it exits; changed,
// or a SIGHUP, restarts the webhook pusher on the new config. SIGTERM and SIGINT end it cleanly through the shutdown
// hook, which only asks main to stop and waits while main removes the record, the key file, the socket and helper.json.
@file:JvmName("PeerHelperMain")

package splice.app.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.WallClock
import splice.sessions.peer.helper.HELPER_UNREGISTERED_EXIT
import splice.sessions.peer.helper.HelperSetup
import splice.sessions.peer.helper.HelperStart
import splice.sessions.peer.helper.PeerHelper
import splice.sessions.peer.helper.PeerSockets
import splice.sessions.peer.helper.RunningHelper
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// why: EX_USAGE from sysexits.h; only a daemon that spelled the command line wrong sees it.
private const val USAGE_EXIT = 64

// why: the stop removes four files and finishes the line in hand; past this the JVM ends regardless.
private const val STOP_WAIT_MS = 10_000L

// why: a frame already in hand finishes its journal append and receipt; this bounds that wait at stop.
private const val DRAIN_MS = 3_000L

private const val SETTINGS = "--settings"
private const val STATE = "--state"
private const val BUILD = "--build"

public suspend fun main(args: Array<String>) {
    exitProcess(PeerHelperRun(args.toList()).run())
}

/** The helper's command line, as PeerSupervisor's HelperLaunch writes it. */
internal class HelperArgs(private val args: List<String>) {
    fun setup(): HelperSetup? {
        val name = args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: return null
        val flags = listOf(SETTINGS, STATE, BUILD).associateWith(::one)
        val registries = all("--registry")
        if (registries.isEmpty() || flags.containsValue(null)) return null
        val sockets = PeerSockets(one("--runtime")?.let(Path::of), uid(), System.getProperty("user.name").orEmpty())
        val settings = Path.of(flags[SETTINGS].orEmpty())
        val state = Path.of(flags[STATE].orEmpty())
        return HelperSetup(name, settings, state, registries.map(Path::of), sockets, flags[BUILD].orEmpty())
    }

    private fun one(flag: String): String? = all(flag).lastOrNull()

    private fun all(flag: String): List<String> =
        args.indices.filter { args[it] == flag && it + 1 < args.size }.map { args[it + 1] }

    /** This process's uid, read off /proc/self, which the kernel makes the process's own. */
    private fun uid(): Long? = Cancellables.runCatchingCancellable {
        (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as? Int)?.toLong()
    }.fold(onSuccess = { it }, onFailure = { null })
}

internal class PeerHelperRun(private val args: List<String>) {
    private val dispatchers = ProcessDispatchers()
    private val clock = WallClock { System.currentTimeMillis() }

    suspend fun run(): Int {
        val setup = HelperArgs(args).setup() ?: return USAGE_EXIT
        return when (val start = PeerHelper(setup, clock).start()) {
            is HelperStart.Started -> serve(setup, start.helper)
            HelperStart.Unregistered -> HELPER_UNREGISTERED_EXIT
            HelperStart.AlreadyRunning -> 0
            is HelperStart.Failed -> 1
        }
    }

    private suspend fun serve(setup: HelperSetup, helper: RunningHelper): Int {
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
        try {
            HelperLoops(setup, helper, scope, stop).start()
            stop.await()
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(DRAIN_MS) { scope.coroutineContext[Job]?.cancelAndJoin() }
                helper.close()
                stopped.countDown()
            }
        }
        return 0
    }
}
