// NEW: Oct 28, 2026 (Marcos: "yeah replcace the java process per peer"; docs/specs/peer-messaging.md 4.1) — the peers
// one peer-host process serves. Each runs as its own helper had: its inbox socket, its record, its heartbeat, its look
// at peer.json, its webhook pusher and, for a codex peer, its hand-overs (HelperLoops.kt). Each stands under a pid of
// its own, an anchor the host starts: `cat` reading a pipe only the host writes to, with SIGHUP ignored. The registry
// record, helper.json and the socket name that pid, so ListAgents and SendMessage see one session per peer. The
// supervisor's SIGTERM to it ends that peer alone, and a host that dies closes the pipe, so every anchor ends with it
// and no peer looks alive with nothing behind it.
package splice.app.peer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.WallClock
import splice.sessions.peer.helper.HelperSetup
import splice.sessions.peer.helper.HelperStart
import splice.sessions.peer.helper.PeerHelper
import splice.sessions.peer.helper.RunningHelper
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.util.concurrent.ConcurrentHashMap

// why: a frame already in hand finishes its journal append and receipt; this bounds that wait at a peer's stop.
private const val DRAIN_MS = 3_000L

/** The script an anchor runs: SIGHUP ignored, which exec keeps, then `cat` until the host's pipe closes. */
private const val ANCHOR_SCRIPT = "trap '' HUP; exec cat"

/** The peers this host serves, by name. */
internal class PeerHost(
    private val args: HelperArgs,
    private val scope: CoroutineScope,
    private val clock: WallClock,
) {
    private val dispatchers = ProcessDispatchers()
    private val hosted = ConcurrentHashMap<String, Hosted>()

    fun count(): Int = hosted.size

    /** Serves [name] unless it is served already; waits for a stopping one to finish first. */
    suspend fun start(name: String) {
        hosted[name]?.let { running ->
            if (running.anchor.isAlive) return
            running.done.join()
        }
        val setup = args.setup(name) ?: return
        val anchor = anchor() ?: return
        when (val start = PeerHelper(setup.copy(anchor = anchor.pid()), clock).start()) {
            is HelperStart.Started -> Hosted(name, setup, anchor, start.helper).also { hosted[name] = it }.run()
            HelperStart.Unregistered, HelperStart.AlreadyRunning, is HelperStart.Failed -> anchor.destroy()
        }
    }

    /** Every peer looks at its peer.json now. */
    fun look() {
        hosted.values.forEach { it.loops.look() }
    }

    /** Stops every peer and waits for each to clean up. */
    suspend fun close() {
        val all = hosted.values.toList()
        all.forEach { it.stop.complete(Unit) }
        all.map { it.done }.joinAll()
    }

    private fun anchor(): Process? = Cancellables.runCatchingCancellable {
        ProcessBuilder("/bin/sh", "-c", ANCHOR_SCRIPT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.fold(onSuccess = { it }, onFailure = { null })

    /** One served peer: its helper's loops in a scope of their own, ended by a stop or by its anchor's exit. */
    private inner class Hosted(
        private val name: String,
        setup: HelperSetup,
        val anchor: Process,
        private val helper: RunningHelper,
    ) {
        val stop = CompletableDeferred<Unit>()
        private val own = LifecycleScope(dispatchers.background())
        val loops = HelperLoops(setup, helper, own, stop)

        /** Ends once the peer has stopped and cleaned up; [run] starts it. */
        val done: Job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                stop.await()
            } finally {
                withContext(NonCancellable) { end() }
            }
        }

        fun run() {
            loops.start()
            own.launch {
                runInterruptible(dispatchers.io()) { anchor.waitFor() }
                stop.complete(Unit)
            }
            done.start()
        }

        private suspend fun end() {
            withTimeoutOrNull(DRAIN_MS) { own.coroutineContext[Job]?.cancelAndJoin() }
            helper.close()
            anchor.destroy()
            hosted.remove(name, this)
        }
    }
}
