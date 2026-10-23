// NEW: LAN share, phase 2, the round-2 and round-3 reviews (docs/specs/lan-share.md, Bounds). One machine's Sessions
// switch. A machine can be in both of this machine's books, as one that joined it (ShareClients) and one it joined
// (RemotesStore): two links cross sessions with it, its own into this machine and this machine's into it, and an Off on
// either of its cards on the Sharing page must stop both. So sessions cross with it only while every book that holds it
// has its switch on. But anyone on the network may join under any name, so the machine on one card may not be the one
// on the other: each card turns only its own book, and an On lets in only the machine its card shows.
package splice.share

import splice.share.remote.RemotesStore

/** Read at each call, so a switch the page turns, or a re-join turns off, takes effect with no restart. */
public class SessionsSwitch(state: ShareState) {
    private val clients = ShareClients(state.clientsFile)
    private val remotes = RemotesStore(state.remotesFile)

    /** Whether sessions cross with [name]: a book holds it, and each book that does has its switch on. */
    public fun on(name: String): Boolean {
        val joined = clients.all().firstOrNull { it.name == name }
        val used = name in remotes.names()
        return (joined != null || used) && joined?.sessions != false && (!used || remotes.sessions(name))
    }

    /** Turns [on] or off the switch on the card of [name] as a machine that joined this one, in that book alone; false
     *  when no machine of that name joined. Throws IOException, as the book's own change does. */
    public fun turnJoined(name: String, on: Boolean): Boolean = clients.allowSessions(name, on)

    /** Turns [on] or off the switch on the card of [name] as a machine this one joined, in that book alone, where this
     *  machine's link reads it; false when this machine joined none of that name. Throws IOException, as
     *  [turnJoined] does. */
    public fun turnUsed(name: String, on: Boolean): Boolean = remotes.allowSessions(name, on)
}
