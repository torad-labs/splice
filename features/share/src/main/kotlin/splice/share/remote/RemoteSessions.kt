// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). Sessions across machines, the joining side: for
// each machine this one joined, a look every few seconds reads that machine's running sessions, mirrors them here as
// `<name>@<machine>` (SessionMirrors), and offers this machine's own there, so each side lists the other's. A message a
// session here writes to a mirror goes to that machine at once; one its sessions write to this machine's mirrors there
// waits in its outbox, which a collection held open here picks up and delivers. A machine that does not answer has no
// session mirrored here until it does.
package splice.share.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirroredSession
import splice.sessions.mirror.SessionMirrors
import splice.sessions.peer.DeliveryStatus
import splice.share.ShareNote
import splice.share.ShareOutboxNote
import splice.share.ShareSession
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker

public class RemoteSessions(
    private val links: RemoteLinks,
    private val mirrors: SessionMirrors,
    private val log: LogSink,
    /** How often each joined machine's sessions are looked at: how soon one that starts or ends shows on the other. */
    private val lookMs: Long = REMOTE_LOOK_MS,
    private val ticker: Ticker = ProcessTicker(),
) {
    private val io = ProcessDispatchers().io()

    /** Looks at and collects from every joined machine until [scope] ends. Null when this one joined none. */
    public fun start(scope: CoroutineScope): Job? {
        val joined = links.names().mapNotNull { name -> links.url(name)?.let { name to it } }
        if (joined.isEmpty()) return null
        return scope.launch {
            for ((name, url) in joined) {
                launch { look(name, url) }
                launch { collect(name, url) }
            }
        }
    }

    /** Mirrors [name]'s sessions here and offers this machine's there, every [lookMs]. A machine that does not answer
     *  has its mirrors withdrawn, which is said once, and so is its coming back. */
    private suspend fun look(name: String, url: String) {
        val carrier = carrierTo(name, url)
        var failing = false
        do {
            val looked = Cancellables.runCatchingCancellable {
                runInterruptible(io) {
                    val api = links.api(name)
                    val theirs = api.sessions(url)
                    val ours = mirrors.local()
                    api.offer(url, ours.map { ShareSession(it.id, it.name, it.heardAt) }, lookMs)
                    theirs.map { MirroredSession(it.id, it.name, it.heardAt) } to ours.size
                }
            }
            looked.fold(
                onSuccess = { (theirs, shown) ->
                    if (failing) log("[remote $name] its sessions are mirrored here again\n")
                    failing = false
                    withContext(io) { mirrors.mirror(name, theirs, carrier) }
                    mirrors.shown(name, shown)
                },
                onFailure = { failure ->
                    if (!failing) log("[remote $name] its sessions are not mirrored here: ${said(failure)}\n")
                    failing = true
                    withContext(io) { mirrors.withdraw(name) }
                },
            )
        } while (currentCoroutineContext().isActive && ticker.awaitTick(lookMs))
    }

    /** Delivers what [name]'s sessions write to this machine's, as it arrives. A collection that fails waits for the
     *  next look before it tries again; the look says why the machine does not answer. */
    private suspend fun collect(name: String, url: String) {
        do {
            val collected = Cancellables.runCatchingCancellable { runInterruptible(io) { links.api(name).outbox(url) } }
            val notes = collected.fold(onSuccess = { it }, onFailure = { null })
            notes?.forEach { deliver(name, it) }
            val again = notes != null || ticker.awaitTick(lookMs)
        } while (currentCoroutineContext().isActive && again)
    }

    private suspend fun deliver(name: String, note: ShareOutboxNote) {
        val from = MirroredSession(note.from.id, note.from.name)
        val carried = mirrors.deliver(note.to.id, name, from, note.text)
        carried.detail?.let { why ->
            log("[remote $name] a note from ${LogSafe.str(from.name)} was not delivered: ${LogSafe.str(why)}\n")
        }
    }

    /** What a mirror of one of [name]'s sessions hands a message to: a note to that session there, now. */
    private fun carrierTo(name: String, url: String) = MirrorCarrier { to, from, text ->
        val note = ShareNote(ShareSession(from.id, from.name), text)
        val answered = Cancellables.runCatchingCancellable {
            runInterruptible(io) { links.api(name).note(url, to.id, note) }
        }
        answered.fold(
            onSuccess = { answer ->
                MirrorCarried(DeliveryStatus.entries.firstOrNull { it.wire == answer.status }, answer.detail)
            },
            onFailure = { MirrorCarried(DeliveryStatus.DROPPED, said(it)) },
        )
    }

    private fun said(failure: Throwable): String =
        (failure as? RemoteUnanswered)?.said ?: SafeFailureText.render(failure)
}

// why: a session that starts or ends shows on the other machine within ten seconds, the goal's bound, and a look is two
// small requests per joined machine.
private const val REMOTE_LOOK_MS = 5_000L
