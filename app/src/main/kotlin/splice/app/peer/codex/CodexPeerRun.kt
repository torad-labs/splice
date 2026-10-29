// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): what a codex peer's helper runs beside its inbox. It
// opens its thread on the app-server, shows the thread's status in the peer's record (`busy` while a turn runs, `idle`
// otherwise), and hands the thread each message the inbox journals. It ends when the thread closes or unloads, when the
// app-server closes the connection, or when a hand-over cannot go out; the helper then journals `ended` and stops, and
// the daemon revokes the peer. A thread that loads again attaches again from its next hook. A thread no app-server holds
// (Oct 28: `codex resume --no-daemon` serves its thread in its own process and listens on no socket) whose Codex process
// still runs keeps its peer: each message waits in CodexHookMailbox for the thread's next hook, and the peer ends with
// that process, as every native-owned peer does.
package splice.app.peer.codex

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.CodexDelivery
import splice.sessions.peer.codex.CodexHookMailbox
import splice.sessions.peer.codex.CodexHookThread
import splice.sessions.peer.codex.CodexReturns
import splice.sessions.peer.helper.RunningHelper
import splice.sessions.peer.journal.PeerJournal

private const val STATUS_CHANGED = "thread/status/changed"
private const val CLOSED = "thread/closed"
private const val ACTIVE = "active"

/** The parts a codex peer's run works with: its helper, its journal, where refusals go, and the log. */
internal data class CodexPeerParts(
    val helper: RunningHelper,
    val journal: PeerJournal,
    val returns: CodexReturns,
    val log: LogSink,
)

/** How a run on the app-server ended: why, and whether the app-server ever held the thread. */
private data class AppServerEnd(val why: String, val held: Boolean)

internal class CodexPeerRun(private val binding: CodexBinding, private val parts: CodexPeerParts) {
    /** Runs until the thread can no longer be reached; why. A thread no app-server holds, hosted by a Codex process,
     *  gets its messages at its next hook instead, for as long as that process runs. */
    suspend fun run(io: CoroutineDispatcher): String {
        val ended = when (val opened = CodexAppServers(io).open(binding)) {
            is CodexOpen.Gone -> AppServerEnd(opened.reason, held = false)
            is CodexOpen.Open -> serve(opened.thread)
        }
        if (ended.held || binding.owner == null) return ended.why
        parts.log("[peers] ${ended.why}, so each message waits for the thread's next hook\n")
        parts.helper.busy(false)
        val mailbox = CodexHookMailbox(parts.helper.files.dir)
        return CodexDelivery(parts.journal, CodexHookThread(mailbox), parts.returns, parts.log).run()
    }

    private suspend fun serve(thread: CodexAppThread): AppServerEnd = coroutineScope {
        val reading = async { thread.read() }
        val ended = when (val greeting = thread.greet()) {
            is CodexGreeting.Refused -> AppServerEnd(greeting.reason, held = false)
            is CodexGreeting.Loaded -> {
                parts.helper.busy(greeting.status == ACTIVE)
                val handing = async { CodexDelivery(parts.journal, thread, parts.returns, parts.log).run() }
                val watching = async { watch(thread) }
                val why = select {
                    handing.onAwait { it }
                    watching.onAwait { it }
                }
                handing.cancel()
                watching.cancel()
                AppServerEnd(why, held = true)
            }
        }
        thread.close()
        reading.await()
        ended
    }

    /** Follows the thread's notifications until it closes or unloads; why it stopped. */
    private suspend fun watch(thread: CodexAppThread): String {
        for (event in thread.notifications) {
            val mine = thread.mine(event.params)
            val status = JsonScalars.str(event.params?.get("status") as? JsonObject, "type")
            when {
                !mine -> Unit
                event.method == CLOSED -> return "the thread closed"
                event.method == STATUS_CHANGED && status == NOT_LOADED -> return "the thread unloaded"
                event.method == STATUS_CHANGED && status != null -> parts.helper.busy(status == ACTIVE)
                else -> Unit
            }
        }
        return "the app-server closed the connection"
    }
}
