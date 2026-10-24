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
// later, so no one failure ends a machine's link. Since round 14, each note is acknowledged on disk as it is handed
// over, and a stop lands between two notes, so a stop part way through a collection hands none of them over again; a
// collection that fails is said in the log once while it keeps failing, as a look is, and so is its coming back.
// Since round 16, a machine that answers a look has its sessions mirrored here whether or not it takes this machine's
// offer. Since round 17, a note's status there that this build has no word for, as a newer or an older build may name,
// tells its sender the note may not have arrived, never that it is on its way.
package splice.share.remote

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import splice.core.util.TerminalText
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirrorLink
import splice.sessions.mirror.MirrorWay
import splice.sessions.mirror.MirroredSession
import splice.sessions.mirror.SessionMirrors
import splice.sessions.peer.DeliveryStatus
import splice.share.ShareLease
import splice.share.ShareNote
import splice.share.ShareNoteAnswer
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

    /** How long a machine's mirrors here last without another look that answers, as on the other side. */
    private val lasts = ShareLease.of(lookMs)

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
                    val link = MirrorLink(carrierTo(name, url), lasts, MirrorWay.LOOK)
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
     *  every [lookMs]. A machine that does not answer has its mirrors withdrawn, which is said once, and so is its
     *  coming back. One that answers keeps its sessions listed here though it does not take this machine's (offered). A
     *  switch turned off here withdraws both sides at the next look: [name] is offered none of this machine's. */
    private suspend fun look(name: String, url: String, link: MirrorLink) {
        val state = RemoteLookState()
        do {
            if (links.sessions(name)) looked(name, url, link, state) else switchedOff(name, url, state)
        } while (currentCoroutineContext().isActive && ticker.awaitTick(lookMs))
    }

    /** [name]'s Sessions switch is off here: its mirrors go, and this machine's there where it holds any. Said once
     *  while it stays off. */
    private suspend fun switchedOff(name: String, url: String, state: RemoteLookState) {
        if (!state.off) log("[remote $name] no session crosses with it: Sessions is off for it on the Sharing page\n")
        if (state.offered) leftThere(name, url)
        withContext(io) { mirrors.withdraw(name, MirrorWay.LOOK) }
        state.switchedOff()
    }

    /** One look at [name], whose switch is on: its sessions read, this machine's offered there once it answers, and
     *  then its own mirrored here, so a mirror that cannot be made holds back neither side. One that does not answer
     *  has its mirrors withdrawn and is asked nothing more until the next look. */
    private suspend fun looked(name: String, url: String, link: MirrorLink, state: RemoteLookState) {
        state.off = false
        val looked = Cancellables.runCatchingCancellable {
            runInterruptible(io) {
                links.api(name).sessions(url).map { MirroredSession(it.id, it.name, it.heardAt) }
            }
        }
        looked.onFailure { failure ->
            if (!state.failing) log("[remote $name] its sessions are not mirrored here: ${said(failure)}\n")
            withContext(io) { mirrors.withdraw(name, MirrorWay.LOOK) }
        }
        if (state.failing && looked.isSuccess) log("[remote $name] its sessions are mirrored here again\n")
        state.failing = looked.isFailure
        looked.onSuccess { theirs ->
            val taken = offered(name, url, state.refused)
            state.refused = !taken
            state.offered = state.offered || taken
            withContext(io) { mirrors.mirror(name, theirs, link) }
        }
    }

    /** Offers [name] this machine's sessions, and answers whether it took them. One it does not take is said once while
     *  it keeps refusing ([refused] says it did last time), and so is its taking them again; its own sessions stay
     *  listed here either way, since it answered. */
    private suspend fun offered(name: String, url: String, refused: Boolean): Boolean {
        val shown = Cancellables.runCatchingCancellable {
            runInterruptible(io) {
                val ours = mirrors.local()
                links.api(name).offer(url, ours.map { ShareSession(it.id, it.name, it.heardAt) }, lookMs)
                ours.size
            }
        }
        return shown.fold(
            onSuccess = { count ->
                if (refused) log("[remote $name] it takes this machine's sessions again\n")
                mirrors.shown(name, count)
                true
            },
            onFailure = { failure ->
                if (!refused) log("[remote $name] it does not take this machine's sessions: ${said(failure)}\n")
                false
            },
        )
    }

    /** Tells [name] that none of this machine's sessions cross with it now, by an offer of none, so its mirrors of them
     *  go at once rather than when their time ends; one it does not take ends with their time all the same. */
    private suspend fun leftThere(name: String, url: String) {
        val told = Cancellables.runCatchingCancellable {
            runInterruptible(io) { links.api(name).offer(url, emptyList(), lookMs) }
        }
        Cancellables.discard(told, "a machine not told keeps this one's mirrors only until their time ends")
    }

    /** Delivers what [name]'s sessions write to this machine's, as it arrives, and acknowledges it with the next
     *  collection, so [name] holds a note until it knows it arrived. A collection that fails is said in the log once
     *  while it keeps failing, and so is its coming back, and waits for the next look before it tries again. While
     *  [name]'s Sessions switch is off it collects nothing, and a collection the switch went off under delivers nothing:
     *  its notes wait there. A note whose sender has no mirror here, made by none of [name]'s looks since a withdraw, is
     *  delivered from one made with [link], so a reply goes back the same way. */
    private suspend fun collect(name: String, url: String, link: MirrorLink) {
        var failing = false
        do {
            val answer = if (links.sessions(name)) collected(name, url, acks.of(name)) else null
            answer?.onFailure { if (!failing) log("[remote $name] its notes are not collected: ${said(it)}\n") }
                ?.onSuccess { outbox ->
                    if (failing) log("[remote $name] its notes are collected again\n")
                    if (links.sessions(name)) handed(name, outbox, link)
                }
            failing = answer?.isFailure ?: failing
            val again = answer?.isSuccess == true || ticker.awaitTick(lookMs)
        } while (currentCoroutineContext().isActive && again)
    }

    /** One collection of [name]'s outbox, acknowledging [ack]. */
    private suspend fun collected(name: String, url: String, ack: ShareOutboxAck?): Result<ShareOutbox> =
        Cancellables.runCatchingCancellable { runInterruptible(io) { links.api(name).outbox(url, ack) } }

    /** Hands each of [outbox]'s notes to its session here, and acknowledges each on disk (RemoteAcks) once it is handed
     *  over, whatever stops the link then, so the collection after a restart here acknowledges it too. A stop lands
     *  between two notes: those after it wait there for the next collection. */
    private suspend fun handed(name: String, outbox: ShareOutbox, link: MirrorLink) {
        for (note in outbox.notes) {
            if (!currentCoroutineContext().isActive) return
            deliver(name, note, link)
            val box = outbox.box ?: continue
            withContext(NonCancellable + io) { acks.delivered(name, ShareOutboxAck(box, note.seq)) }
        }
    }

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
            onSuccess = { answer -> carried(name, answer) },
            onFailure = { MirrorCarried(DeliveryStatus.DROPPED, said(it)) },
        )
    }

    /** What [name]'s answer to a note tells its sender: the status it named, with why; nothing while the note is on its
     *  way; and for a status this build has no word for, that the note may not have arrived, with what [name] said. */
    private fun carried(name: String, answer: ShareNoteAnswer): MirrorCarried {
        val status = answer.status ?: return MirrorCarried(null, answer.detail)
        val known = DeliveryStatus.entries.firstOrNull { it.wire == status }
        if (known != null) return MirrorCarried(known, answer.detail)
        val named = TerminalText.line(status, STATUS_SHOWN_CHARS)
        val why = "$name answered \"$named\", which splice here does not know, so the note may not have arrived"
        return MirrorCarried(DeliveryStatus.DROPPED, answer.detail?.let { "$why: $it" } ?: why)
    }

    private fun said(failure: Throwable): String =
        (failure as? RemoteUnanswered)?.said ?: SafeFailureText.render(failure)
}

/** One of a joined machine's two loops, its look or its collection, which runs until the link ends. */
private fun interface LinkLoop {
    suspend fun run()
}

// why: a status word is a few letters; past 40, one this build does not know is shown cut, as a label is.
private const val STATUS_SHOWN_CHARS = 40

// why: a session that starts or ends shows on the other machine within ten seconds, the goal's bound, and a look is two
// small requests per joined machine.
private const val REMOTE_LOOK_MS = 5_000L
