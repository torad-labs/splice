// NEW: peer messaging (docs/specs/peer-messaging.md 1.3 and 3.3) — who a peer's send goes to, and who an inbound frame is from,
// both read from the live registry and never from a request. A target is SessionNoteRoute's: one running record, alone on its
// socket. A sender is `registered` when its claimed address is one live record's socket, which says where the bytes claim to
// come from and never proves who wrote them.
package splice.sessions.note

import splice.sessions.peer.SenderCheck
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

// why: a session-id prefix shorter than 8 hex characters matches too many sessions to name one (spec 3.3).
private const val MIN_PREFIX_CHARS = 8

/** What a send's `to` resolved to. Every case but [One] carries the reason the route answers with. */
public sealed class PeerTarget {
    public data class One(val record: SessionRecord) : PeerTarget()

    public data class None(val reason: String) : PeerTarget()

    public data class Ambiguous(val reason: String) : PeerTarget()

    public data class NotRunning(val reason: String) : PeerTarget()

    public data class NoSocket(val reason: String) : PeerTarget()

    public data class SharedSocket(val reason: String) : PeerTarget()
}

public class PeerTargets(private val registry: SessionSource) {
    /** The one running record [query] names: an exact session id first, then a name, then a session-id prefix of at
     *  least 8 characters. Each kind is tried only when the kind before it matched no record at all. */
    public fun resolve(query: String): PeerTarget {
        val all = registry.read()
        val named = matches(all, query)
        if (named.isEmpty()) return PeerTarget.None("no session or peer is called $query")
        val running = named.filter { it.availability != SessionAvailability.GONE }
        val record = running.singleOrNull()
        val socket = record?.process?.messagingSocketPath
        val live = all.filter { it.availability != SessionAvailability.GONE }
        return when {
            running.isEmpty() -> PeerTarget.NotRunning("$query is not running")
            record == null -> PeerTarget.Ambiguous("more than one running session or peer answers to $query")
            socket == null -> PeerTarget.NoSocket("$query has no messaging socket")
            live.count { it.process.messagingSocketPath == socket } > 1 ->
                PeerTarget.SharedSocket("more than one running session answers on the socket of $query")
            else -> PeerTarget.One(record)
        }
    }

    /** Who a frame claiming [address] is from. A hit is the one running record on that socket, and its name and session
     *  id are the registry's; a miss keeps only what the frame claimed. [claimedName] is kept either way. */
    public fun byAddress(address: String?, claimedName: String? = null): SenderCheck {
        val socket = address?.takeIf { it.startsWith("uds:") }?.removePrefix("uds:")
        val record = socket?.let { path ->
            registry.read().filter {
                it.availability != SessionAvailability.GONE && it.process.messagingSocketPath == path
            }.singleOrNull()
        }
        return if (record == null) {
            SenderCheck(claimedName, null, address, claimedName, registered = false)
        } else {
            SenderCheck(record.name, record.sessionId, address, claimedName, registered = true)
        }
    }

    private fun matches(all: List<SessionRecord>, query: String): List<SessionRecord> {
        val byId = all.filter { it.sessionId == query }
        val byName = all.filter { it.name == query }
        val byPrefix = all.filter { query.length >= MIN_PREFIX_CHARS && it.sessionId?.startsWith(query) == true }
        return listOf(byId, byName, byPrefix).firstOrNull { it.isNotEmpty() }.orEmpty()
    }
}
