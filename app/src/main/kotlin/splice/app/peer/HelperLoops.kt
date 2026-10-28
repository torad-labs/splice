// NEW: peer messaging, package B (docs/specs/peer-messaging.md 4.1 and 4.2) — what a started peer helper runs, moved
// verbatim out of PeerHelperMain.kt: the inbox socket, the registry heartbeat every 30 s, the look at peer.json every
// 10 s, and the webhook pusher, started again on a changed peer.json or a SIGHUP. A codex peer's helper (Oct 27, 2026)
// also hands its messages to its Codex thread, and stops once the thread can no longer be reached.
// A persisted native owner supplies a cancellable process-exit event; neither the helper's parent nor the daemon does.
package splice.app.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import splice.app.peer.codex.CodexPeerParts
import splice.app.peer.codex.CodexPeerRun
import splice.app.peer.owner.PeerOwnerWatch
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.sessions.note.PeerNoteSocket
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerSender
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.CodexLedger
import splice.sessions.peer.codex.CodexReturns
import splice.sessions.peer.helper.HelperSetup
import splice.sessions.peer.helper.PeerFileState
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
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import splice.upstream.codemode.ProcessWaiter
import sun.misc.Signal

// why: grokbot's period, and well inside SessionRegistry's 30-minute stale window (spec 2.2).
private const val HEARTBEAT_MS = 30_000L

// why: the spec's check for a removed peer.json (4.1); a revoke stops the helper itself within this.
private const val LOOK_MS = 10_000L

/** Every registry folder the daemon reads, as one source for PeerTargets. */
internal class RegistryFolders(private val folders: List<SessionSource>) : SessionSource {
    override fun read(): List<SessionRecord> = folders.flatMap { it.read() }

    override fun list(): SessionListing {
        val listings = folders.map { it.list() }
        return SessionListing(listings.flatMap { it.sessions }, listings.firstNotNullOfOrNull { it.error })
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
        helper.parts.owner?.let { owner ->
            scope.launch {
                val watched = Cancellables.runCatchingCancellable { PeerOwnerWatch().await(owner) }
                watched.exceptionOrNull()?.let { log.error("native owner", it) }
                log.note("native owner ended")
                stop.complete(Unit)
            }
        }
        PeerStore(setup.settingsDir).codexThreads[setup.name]?.let { binding ->
            scope.launch { codex(binding, CodexReturns(outbox, sender)) }
        }
        listenForHangup()
        helper.heartbeat()
    }

    /** A codex peer hands its messages to its thread until the thread can no longer be reached, then the helper
     *  journals that the thread ended and stops, and the daemon revokes the peer (docs/peers.md). */
    private suspend fun codex(binding: CodexBinding, returns: CodexReturns) {
        val parts = CodexPeerParts(helper, journal, returns, sink)
        val ran = Cancellables.runCatchingCancellable { CodexPeerRun(binding, parts).run(io) }
        ran.exceptionOrNull()?.let { log.error("codex", it) }
        val why = ran.getOrElse { "the codex peer failed: ${SafeFailureText.render(it)}" }
        log.note("codex thread ended", why)
        CodexLedger(journal).ended(why)
        stop.complete(Unit)
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
