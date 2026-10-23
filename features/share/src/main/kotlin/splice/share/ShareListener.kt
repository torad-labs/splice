// NEW: LAN share, phase 2, milestone 4 (docs/specs/lan-share.md). The share listener as `[share]` says it now. The
// daemon opens it at start with the table it booted with, and every later version of the table reaches it: the
// console's Sharing page hands its write here at once, and the daemon's re-read of splice.toml (TopologyWindows) hands
// any other edit, `splice share on|off` and an edit by hand included. So sharing turns on and off, and changes the
// commands it offers, without a daemon restart. A table that moved closes the listener and opens it again with the new
// one; the machines that joined and their activity are one book across those reopenings. Since milestone 5, while it
// listens it advertises itself on this machine's networks (ShareAdvert), and it stops when the listener closes.
package splice.share

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.core.config.StatePaths
import splice.core.topology.ShareConfig
import splice.core.util.LogSink
import splice.sessions.mirror.SessionMirrors
import splice.upstream.codemode.ProcessDispatchers

public class ShareListener(
    private val opener: ShareStart,
    /** The heads the listener offers, read at each request (ShareHeads). */
    private val running: SharedCatalogs,
    private val quotas: SharedQuotas,
    statePaths: StatePaths,
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
    public val switch: SessionsSwitch = SessionsSwitch(ShareState(statePaths))

    /** The session routes' mirrors, and what sessions here wrote to them: one across reopenings, as [clients] is. */
    private val sessions = ShareSessionParts(mirrors, switch)

    @Volatile
    private var server: ShareServer? = null

    @Volatile
    private var followed: ShareListening = ShareListening(ShareConfig(), listening = false)

    /** The listener as it is now. */
    public fun state(): ShareListening = followed

    /** Opens, closes or reopens the listener so it serves [share]; nothing when it already does. A table that is the
     *  same but did not open (a port another program held) is tried again. Answers the listener as it is after. */
    public suspend fun follow(share: ShareConfig): ShareListening = mutex.withLock {
        val was = followed
        if (was.serves(share)) return@withLock was
        runInterruptible(ProcessDispatchers().io()) { reopened(was, share) }
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
}
