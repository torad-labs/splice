// NEW: LAN share, phase 2, the round-2 review (docs/specs/lan-share.md, Bounds). One machine's Sessions switch. A
// machine can be in both of this machine's books, as one that joined it (ShareClients) and one it joined
// (RemotesStore), and it is one machine either way: two links cross sessions with it, its own into this machine and this
// machine's into it, and an Off on either of its cards on the Sharing page must stop both. So sessions cross with it
// only while every book that holds it has its switch on, and the page turns it in both.
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

    /** Turns [name]'s switch [on] or off in each book that holds it; false when neither does. Throws IOException, as
     *  each book's own change does. */
    public fun turn(name: String, on: Boolean): Boolean {
        val joined = clients.allowSessions(name, on)
        val used = remotes.allowSessions(name, on)
        return joined || used
    }
}
