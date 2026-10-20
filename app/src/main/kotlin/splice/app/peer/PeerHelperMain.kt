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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.sessions.note.PeerNoteSocket
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerSender
import splice.sessions.peer.helper.HELPER_UNREGISTERED_EXIT
import splice.sessions.peer.helper.HelperSetup
import splice.sessions.peer.helper.HelperStart
import splice.sessions.peer.helper.PeerFileState
import splice.sessions.peer.helper.PeerHelper
import splice.sessions.peer.helper.PeerSockets
import splice.sessions.peer.helper.RunningHelper
import splice.sessions.peer.journal.PeerInbox
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.peer.store.PeerStore
import splice.sessions.peer.webhook.WebhookPusher
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import splice.upstream.codemode.ProcessWaiter
import sun.misc.Signal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// why: EX_USAGE from sysexits.h; only a daemon that spelled the command line wrong sees it.
private const val USAGE_EXIT = 64

// why: grokbot's period, and well inside SessionRegistry's 30-minute stale window (spec 2.2).
private const val HEARTBEAT_MS = 30_000L

// why: the spec's check for a removed peer.json (4.1); a revoke stops the helper itself within this.
private const val LOOK_MS = 10_000L

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

/** Every registry folder the daemon reads, as one source for PeerTargets. */
internal class RegistryFolders(private val folders: List<SessionSource>) : SessionSource {
    override fun read(): List<SessionRecord> = folders.flatMap { it.read() }

    override fun list(): SessionListing {
        val listings = folders.map { it.list() }
        return SessionListing(listings.flatMap { it.sessions }, listings.firstNotNullOfOrNull { it.error })
    }
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

/** What a started helper runs: the inbox, the webhook pusher, the heartbeat and the look at peer.json. */
internal class HelperLoops(
    private val setup: HelperSetup,
    private val helper: RunningHelper,
    private val scope: CoroutineScope,
    private val stop: CompletableDeferred<Unit>,
) {
    private val io = ProcessDispatchers().io()
    private val ticker = ProcessTicker()
    private val waiter = ProcessWaiter()
    private val log = helper.parts.log
    private val sink = LogSink { line -> log.note("inbox", line.trim()) }
    private val journal = PeerJournal(helper.files.dir, sink)
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        val sender = PeerSender(helper.parts.socket.toString(), helper.config.name)
        val outbox = PeerNoteSocket(io, registryDirs = setup.registryDirs)
        val inbox = PeerInbox(journal, outbox, sender, sink)
        val registry = RegistryFolders(
            setup.registryDirs.map { SessionRegistry(it, RouteOfPid { SessionRoute.Unknown }) },
        )
        val server = helper.server(inbox, PeerTargets(registry), io, System.getProperty("user.name").orEmpty())
        scope.launch {
            Cancellables.runCatchingCancellable { server.serve() }.exceptionOrNull()?.let { log.error("inbox", it) }
            // An inbox that stopped serving is a helper with no reason to stay: the supervisor starts a new one.
            stop.complete(Unit)
        }
        scope.launch { while (ticker.awaitTick(HEARTBEAT_MS)) helper.heartbeat() }
        scope.launch { while (ticker.awaitTick(LOOK_MS)) look() }
        scope.launch { pushes() }
        listenForHangup()
        helper.heartbeat()
    }

    private fun look() {
        when (helper.look()) {
            PeerFileState.GONE -> stop.complete(Unit)
            PeerFileState.CHANGED -> reloads.trySend(Unit)
            PeerFileState.SAME -> Unit
        }
    }

    /** The webhook pusher on the current config, started again on every reload; its cursor is on disk. */
    private suspend fun pushes() {
        var pusher = pusher(helper.config)
        while (true) {
            reloads.receive()
            pusher?.cancelAndJoin()
            if (helper.look() == PeerFileState.GONE) stop.complete(Unit)
            pusher = pusher(helper.config)
        }
    }

    private fun pusher(config: PeerConfig): Job? {
        if (config.webhook?.enabled != true) return null
        val peerDir = PeerStore(setup.settingsDir).dir(setup.name)
        val wait = WebhookPusher.Wait { ms -> waiter.wait(ms) }
        return WebhookPusher(config, journal, peerDir, wait, sink).start(scope)
    }

    /** SIGHUP is the supervisor's reload; the 10 s look is the fallback when the signal cannot be handled here. */
    private fun listenForHangup() {
        val handled = Cancellables.runCatchingCancellable {
            Signal.handle(Signal("HUP")) { reloads.trySend(Unit) }
        }
        handled.exceptionOrNull()?.let { log.error("SIGHUP", it) }
    }
}
