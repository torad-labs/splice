// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). Sessions across machines, the joining side: for
// each machine this one joined, a look every few seconds reads that machine's running sessions, mirrors them here as
// `<machine>/<name>` (SessionMirrors), and offers this machine's own there, so each side lists the other's. A message a
// session here writes to a mirror goes to that machine at once; one its sessions write to this machine's mirrors there
// waits in its outbox, which a collection held open here picks up and delivers from a mirror of its sender, made then
// if none is left. A machine that does not answer has no session mirrored here until it does. Nothing crosses with a
// machine whose Sessions switch is off here (SessionsSwitch): its link neither looks, offers nor collects, and each of
// its looks while the switch is off withdraws its mirrors, so one a collection made as the switch went off goes too.
// Each look gives a machine's mirrors three looks more (SessionMirrors ends them), as its offers do on the other side.
// Since round 13 of PR #419's review, what this machine acknowledged of each outbox is kept on disk (RemoteAcks), so a
// restart hands no note over twice, and a look or a collection that fails in a way nothing foresaw starts again a look
// later, so no one failure ends a machine's link.
package splice.share.remote

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirrorLink
import splice.sessions.mirror.MirroredSession
import splice.sessions.mirror.SessionMirrors
import splice.sessions.peer.DeliveryStatus
import splice.share.LEASE_LOOKS
import splice.share.LEASE_MAX_MS
import splice.share.LEASE_MIN_MS
import splice.share.ShareNote
import splice.share.ShareOutbox
import splice.share.ShareOutboxAck
import splice.share.ShareOutboxNote
import splice.share.ShareSession
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker
import java.nio.file.Path

public class RemoteSessions(
    private val links: RemoteLinks,
    private val mirrors: SessionMirrors,
    /** Where what this machine acknowledged of each joined machine's outbox is kept: ShareState's acksFile. */
    acks: Path,
    private val log: LogSink,
    /** How often each joined machine's sessions are looked at: how soon one that starts or ends shows on the other. */
    private val lookMs: Long = REMOTE_LOOK_MS,
    private val ticker: Ticker = ProcessTicker(),
) {
    private val io = ProcessDispatchers().io()
    private val acks = RemoteAcks(acks, log)

    /** How long a machine's mirrors here last without another look that answers: three looks, as on the other side. */
    private val lasts = (lookMs * LEASE_LOOKS).coerceIn(LEASE_MIN_MS, LEASE_MAX_MS)

    /** Looks at and collects from every joined machine until [scope] ends. Null when this one joined none. Each loop
     *  runs on its own: one that fails is said in the log and starts again a look later, and never stops another
     *  machine's or its other loop. */
    public fun start(scope: CoroutineScope): Job? {
        val joined = links.names().mapNotNull { name -> links.url(name)?.let { name to it } }
        if (joined.isEmpty()) return null
        val stopped = CoroutineExceptionHandler { _, failure ->
            log("[remote] a session link stopped: ${SafeFailureText.render(failure)}\n")
        }
        return scope.launch(stopped) {
            supervisorScope {
                for ((name, url) in joined) {
                    val link = MirrorLink(carrierTo(name, url), lasts)
                    launch { carryOn(name, "look", LinkLoop { look(name, url, link) }) }
                    launch { carryOn(name, "collection", LinkLoop { collect(name, url, link) }) }
                }
            }
        }
    }

    /** Runs [loop], [name]'s look or its collection, for as long as the link runs: one that fails in a way nothing in
     *  it foresaw is said in the log and starts again a look later, so no one failure ends a machine's link. */
    private suspend fun carryOn(name: String, what: String, loop: LinkLoop) {
        do {
            val failed = Cancellables.runCatchingBestEffort { loop.run() }
                .fold(onSuccess = { null }, onFailure = { it }) ?: return
            log("[remote $name] its $what failed, and starts again: ${SafeFailureText.render(failed)}\n")
        } while (currentCoroutineContext().isActive && ticker.awaitTick(lookMs))
    }

    /** Mirrors [name]'s sessions here, each carrying a message by [link]'s carrier, and offers this machine's there,
     *  every [lookMs]. A machine that does not answer has its mirrors withdrawn, which is said once, and so is its coming
     *  back. */
    private suspend fun look(name: String, url: String, link: MirrorLink) {
        var failing = false
        var off = false
        do {
            if (!links.sessions(name)) {
                if (!off) log("[remote $name] no session crosses with it: Sessions is off for it on the Sharing page\n")
                withContext(io) { mirrors.withdraw(name) }
                off = true
                failing = false
                continue
            }
            off = false
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
                    withContext(io) { mirrors.mirror(name, theirs, link) }
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

    /** Delivers what [name]'s sessions write to this machine's, as it arrives, and acknowledges it with the next
     *  collection, so [name] holds a note until it knows it arrived. A collection that fails waits for the next look
     *  before it tries again; the look says why the machine does not answer. While [name]'s Sessions switch is off it
     *  collects nothing, and a collection the switch went off under delivers nothing: its notes wait there. A note
     *  whose sender has no mirror here, made by none of [name]'s looks since a withdraw, is delivered from one made
     *  with [link], so a reply goes back the same way. What was handed over is acknowledged on disk (RemoteAcks), so
     *  the collection after a restart here acknowledges it too. */
    private suspend fun collect(name: String, url: String, link: MirrorLink) {
        do {
            val answer = if (links.sessions(name)) collected(name, url, acks.of(name)) else null
            val delivered = answer?.takeIf { links.sessions(name) }
            delivered?.notes?.forEach { deliver(name, it, link) }
            delivered?.acknowledging()?.let { withContext(io) { acks.delivered(name, it) } }
            val again = answer != null || ticker.awaitTick(lookMs)
        } while (currentCoroutineContext().isActive && again)
    }

    /** One collection of [name]'s outbox, acknowledging [ack]; null when it failed. */
    private suspend fun collected(name: String, url: String, ack: ShareOutboxAck?): ShareOutbox? =
        Cancellables.runCatchingCancellable { runInterruptible(io) { links.api(name).outbox(url, ack) } }
            .fold(onSuccess = { it }, onFailure = { null })

    /** Hands [note] to its session here. One whose handing fails in a way nothing foresaw is not delivered, said as a
     *  refused one is, and the notes after it still are: the collection that brought it acknowledges it either way. */
    private suspend fun deliver(name: String, note: ShareOutboxNote, link: MirrorLink) {
        val from = MirroredSession(note.from.id, note.from.name)
        val failed = Cancellables.runCatchingBestEffort { mirrors.deliver(note.to.id, name, from, note.text, link) }
            .fold(onSuccess = { it.detail }, onFailure = { SafeFailureText.render(it) })
        failed?.let { why ->
            log("[remote $name] a note from ${LogSafe.str(from.name)} was not delivered: ${LogSafe.str(why)}\n")
        }
    }

    /** What a mirror of one of [name]'s sessions hands a message to: a note to that session there, now. */
    private fun carrierTo(name: String, url: String): MirrorCarrier = MirrorCarrier { to, from, text ->
        // Its mirrors last their time after the switch goes off; from that moment they carry nothing to it.
        if (!links.sessions(name)) {
            return@MirrorCarrier MirrorCarried(DeliveryStatus.REFUSED, "the Sessions switch for $name is off here")
        }
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

/** One of a joined machine's two loops, its look or its collection, which runs until the link ends. */
private fun interface LinkLoop {
    suspend fun run()
}

// why: a session that starts or ends shows on the other machine within ten seconds, the goal's bound, and a look is two
// small requests per joined machine.
private const val REMOTE_LOOK_MS = 5_000L
