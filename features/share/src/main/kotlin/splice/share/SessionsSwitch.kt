// NEW: LAN share, phase 2, the round-2 and round-3 reviews (docs/specs/lan-share.md, Bounds). One machine's Sessions
// switch. A machine can be in both of this machine's books, as one that joined it (ShareClients) and one it joined
// (RemotesStore): two links cross sessions with it, its own into this machine and this machine's into it, and an Off on
// either of its cards on the Sharing page must stop both. So sessions cross with it only while every book that holds it
// has its switch on. But anyone on the network may join under any name, so the machine on one card may not be the one
// on the other: each card turns only its own book, and an On lets in only the machine its card shows. A machine this
// one joined is one splice.toml's `[remotes]` names now, a card the page draws (the round-4 and round-5 reviews): one
// imported from the page has its switch before splice restarts and starts its link, and a token kept for a name the
// file no longer lists holds no machine back. Round 16 of PR #419's review: two machines may go by one name, imported
// here under `<name>` and `<name>-2`, while the clients book holds only the one that joined last, and nothing in the
// books tells which of the two that is. So each link asks as its book knows the machine, a joined one by its name and
// an imported one by its `[remotes]` key, read back to the name it gave; and an Off on any card of that name stops
// every machine that goes by it, rather than let sessions cross with the one whose card is off.
package splice.share

import splice.share.remote.RemoteNames
import splice.share.remote.RemotesStore

/** The machines this one joined, as splice.toml's `[remotes]` names them now: the cards the Sharing page draws. */
public fun interface MachinesJoined {
    public fun names(): Set<String>
}

/** Read at each call, so a switch the page turns, a re-join turns off, or a machine the page imports takes effect with
 *  no restart. [used] reads the machines this one joined. */
public class SessionsSwitch(state: ShareState, private val used: MachinesJoined) {
    private val clients = ShareClients(state.clientsFile)
    private val remotes = RemotesStore(state.remotesFile)

    /** Whether sessions cross with the machine that joined this one as [name]: its link into this one asks so, and its
     *  card on the Sharing page. Phase 1 booked a machine as it calls itself (`Torad-Server`), an import keys
     *  `[remotes]` by the lower-case spelling, and both read as RemoteNames reads a name. */
    public fun on(name: String): Boolean = crossing(RemoteNames.of(name))

    /** Whether sessions cross with the machine this one joined under the `[remotes]` key [key]: this machine's link
     *  into it asks so, and its card. */
    public fun onUsed(key: String): Boolean = crossing(machineOf(key, remotes.servers()))

    /** Whether sessions cross with [machine], by its RemoteNames spelling: a book holds it, and every card of that name
     *  in each book is on. */
    private fun crossing(machine: String): Boolean {
        val joined = clients.all().filter { it.named(machine) }
        val servers = remotes.servers()
        val cards = used.names().filter { machineOf(it, servers) == machine }
        return (joined.isNotEmpty() || cards.isNotEmpty()) && joined.all { it.sessions } && cards.all(remotes::sessions)
    }

    /** The machine [key] was imported from, by its RemoteNames spelling: the name it gave, kept in [servers], which a
     *  key's `-2` hides; the key itself for one imported before that name was kept. */
    private fun machineOf(key: String, servers: Map<String, String>): String = RemoteNames.of(servers[key] ?: key)

    /** Whether sessions cross with [name] as the machine that joined at [joined]: they do, and the machine booked under
     *  that name now is that one, so a mirror made for a machine since revoked carries nothing to one that joined
     *  after it. */
    internal fun on(name: String, joined: String): Boolean =
        on(name) && clients.all().any { it.named(name) && it.joinedAt == joined }

    /** Turns [on] or off the switch on the card of [name] as the machine that joined this one at [joined], in that book
     *  alone; false when no machine of that name joined, or the one booked under it now joined at another time: a card
     *  drawn before a re-join turns nothing of the machine that joined since. Throws IOException, as the book's own
     *  change does. */
    public fun turnJoined(name: String, on: Boolean, joined: String): Boolean = clients.allowSessions(name, on, joined)

    /** When the machine booked under [name] now joined this one, or null when none is. */
    internal fun booked(name: String): String? = clients.all().firstOrNull { it.named(name) }?.joinedAt

    /** Whether a machine of [name] joined this one, whenever it did. */
    public fun joined(name: String): Boolean = clients.all().any { it.named(name) }

    /** Turns [on] or off the switch on the card of [name] as a machine this one joined, in that book alone, where this
     *  machine's link reads it; false when this machine joined none of that name, in [used]. Throws IOException, as
     *  [turnJoined] does. */
    public fun turnUsed(name: String, on: Boolean): Boolean = card(name)?.let { remotes.allowSessions(it, on) } == true

    /** The `[remotes]` key of the machine this one joined under [name], however it is spelled, or null. */
    private fun card(name: String): String? = used.names().firstOrNull { RemoteNames.of(it) == RemoteNames.of(name) }
}
