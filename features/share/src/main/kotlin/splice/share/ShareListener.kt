// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). The share listener as `[share]` says it now. The
// daemon opens it at start with the table it booted with, and every later version of the table reaches it: the
// console's Sharing page hands its write here at once, and the daemon's re-read of splice.toml (TopologyWindows) hands
// any other edit, `splice share on|off` and an edit by hand included. So sharing turns on and off, and changes the
// commands it offers, without a daemon restart. A table whose port, on/off or names moved closes the listener and opens
// it again with the new one; the machines that joined and their activity are one book across those reopenings. One
// that changes only the commands offered is taken by the open listener (round 12 of PR #419's review), so a turn
// relayed through it and a collection held open carry on, as editing which commands are shared cuts no local turn.
// The daemon's stop closes it for good: no table asked for while its heads drain opens it again. Since milestone 5,
// while it listens it advertises itself on this machine's networks (ShareAdvert), and it stops when the listener closes.
package splice.share

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.config.StatePaths
import splice.core.topology.ShareConfig
import splice.core.util.LogSink
import splice.sessions.mirror.SessionMirrors
import splice.upstream.codemode.ProcessDispatchers
import java.util.concurrent.atomic.AtomicReference

public class ShareListener(
    private val opener: ShareStart,
    /** The heads the listener offers, read at each request (ShareHeads). */
    private val running: SharedCatalogs,
    private val quotas: SharedQuotas,
    statePaths: StatePaths,
    /** The machines this one joined, as the Sharing page draws them: what the Sessions switch reads them from. */
    used: MachinesJoined,
    private val log: LogSink,
    /** The daemon's mirrors of other machines' sessions: a joined machine's are kept here, and it is shown this one's. */
    public val mirrors: SessionMirrors? = null,
) {
    private val mutex = Mutex()
    private val advert = SystemMdns().advert(log)

    /** The machines that joined this one, and when each was last heard from. */
    public val clients: ShareClients = ShareClients(ShareState(statePaths).clientsFile)

    /** Each machine's one Sessions switch, read through this machine's two books (the clients above, and the
     *  machines it joined): what the Sharing page reads and turns, and what the session routes let in by. */
    public val switch: SessionsSwitch = SessionsSwitch(ShareState(statePaths), used)

    /** The session routes' mirrors, and what sessions here wrote to them: one across reopenings, as [clients] is. */
    private val sessions = ShareSessionParts(mirrors, switch)

    @Volatile
    private var server: ShareServer? = null

    @Volatile
    private var followed: ShareListening = ShareListening(ShareConfig(), listening = false)

    /** The table last asked for: what [followAsked] serves, whatever order the jobs that call it run in. */
    private val asked = AtomicReference<ShareConfig?>(null)

    /** Set by [stop]: the daemon is stopping, and nothing opens the listener again. */
    @Volatile
    private var stopped = false

    /** The listener as it is now. */
    public fun state(): ShareListening = followed

    /** Revokes [name]: its token stops working, its mirrors leave this machine's listing at once, and the notes waiting
     *  for it go. False when no machine has that name. Throws IOException, as ShareClients.revoke does. Blocking. */
    public fun revoke(name: String): Boolean {
        val booked = clients.all().filter { it.named(name) }.map { it.name }
        val found = clients.revoke(name)
        if (found) booked.forEach(sessions::left)
        return found
    }

    /** Opens, closes or reopens the listener so it serves [share]; nothing when it already does. A table that is the
     *  same but did not open (a port another program held) is tried again. Answers the listener as it is after. */
    public suspend fun follow(share: ShareConfig): ShareListening {
        ask(share)
        return followAsked()
    }

    /** Records [share] as the table to serve, at once, in the order its callers read it: the daemon's re-read of
     *  splice.toml asks on the read's own thread, and [followAsked] then serves the latest asked. */
    public fun ask(share: ShareConfig) {
        asked.set(share)
    }

    /** Serves the table last [ask]ed for, as [follow] does: a job that runs after a later one serves the later table
     *  too, so the listener ends up as the file said last. Nothing before anything is asked, or once [stop] ran. */
    public suspend fun followAsked(): ShareListening = mutex.withLock {
        val share = asked.get() ?: return@withLock followed
        val was = followed
        val open = server
        when {
            stopped || was.serves(share) -> was
            open != null && was.offersOnly(share) -> offering(open, was, share)
            else -> runInterruptible(ProcessDispatchers().io()) { reopened(was, share) }
        }
    }

    /** [open], offering [share]'s commands from now on with no reopening. Under [mutex]. */
    private fun offering(open: ShareServer, was: ShareListening, share: ShareConfig): ShareListening {
        open.offer(share)
        followed = was.copy(share = share, offered = open.offered)
        return followed
    }

    /** Closes what [was] opened and opens what [share] asks for. Blocking, under [mutex]. */
    private fun reopened(was: ShareListening, share: ShareConfig): ShareListening {
        val open = server
        open?.stop()
        server = null
        followed = when (val opened = opener.open(share, clients, running, quotas, sessions)) {
            is ShareOpen.Listening -> {
                server = opened.server
                advert.on(ShareHostName().short(), share.port)
                ShareListening(share, listening = true, opened.server.fingerprint, opened.server.offered)
            }
            is ShareOpen.Refused -> {
                advert.off()
                ShareListening(share, listening = false, why = opened.why)
            }
            ShareOpen.Off -> {
                advert.off()
                if (open != null) log("[share] sharing is off: the listener on :${was.share.port} closed\n")
                ShareListening(share, listening = false)
            }
        }
        return followed
    }

    /** Closes the listener for good: the daemon's stop, before its heads drain. */
    public suspend fun stop() {
        mutex.withLock {
            stopped = true
            runInterruptible(ProcessDispatchers().io()) { server?.stop() }
            advert.off()
            server = null
            followed = followed.copy(listening = false, offered = emptyList())
        }
    }
}

/** The listener as it is: the [share] table it follows, whether it is [listening], the [fingerprint] of the
 *  certificate it serves and the heads it [offered], or [why] it is not listening while the table says it should. */
public data class ShareListening(
    public val share: ShareConfig,
    public val listening: Boolean,
    public val fingerprint: String? = null,
    public val offered: List<String> = emptyList(),
    public val why: String? = null,
) {
    /** Whether this listener already serves [wanted]: the same table, open, or closed because it says off. A table
     *  that did not open is tried again. */
    internal fun serves(wanted: ShareConfig): Boolean = share == wanted && (listening || !wanted.enabled)

    /** Whether this open listener can serve [wanted] as it is: the same table but for the commands it offers. */
    internal fun offersOnly(wanted: ShareConfig): Boolean = listening && share.copy(heads = wanted.heads) == wanted
}
