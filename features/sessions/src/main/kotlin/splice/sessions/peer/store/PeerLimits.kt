// NEW: Oct 27, 2026 (docs/peers.md, "Send as the peer"): how fast a peer may send. Every peer gets the default, a token
// bucket of 20 refilled at 60 a minute, and `splice peer limit` gives one peer its own, kept in limit.json in its
// settings folder beside peer.json (revoking the peer deletes it with the folder). The default lets a program that tells
// a whole team at once (the Temple's tell-seat, after a land) send its burst without a wait, while a script stuck in a
// loop is held to one send a second. The bounds keep a configured limit within what one helper and the sessions it
// writes to can take.
package splice.sessions.peer.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

// why: a team-wide tell after a land is about a dozen messages at once; 20 takes it whole with room to spare.
internal const val PEER_SEND_BURST: Int = 20

// why: one a second, refilled: past any conversation, and a looping script still cannot fill a session's queue of 50
// before a person sees it.
internal const val PEER_SENDS_PER_MINUTE: Int = 60

// why: ten a second is past what one helper's single-threaded socket writes should be asked to sustain.
private const val MAX_SENDS_PER_MINUTE = 600

// why: a burst past 100 could fill two sessions' queues of 50 before the refill means anything.
private const val MAX_SEND_BURST = 100

private const val LIMIT_FILE = "limit.json"

/** A peer's send limit: a bucket of [burst] sends, refilled at [perMinute] a minute. */
@Serializable
public data class PeerLimit(val perMinute: Int = PEER_SENDS_PER_MINUTE, val burst: Int = PEER_SEND_BURST) {
    /** Why this limit may not be set, or null when it may. */
    public fun refusal(): String? =
        if (perMinute in 1..MAX_SENDS_PER_MINUTE && burst in 1..MAX_SEND_BURST) {
            null
        } else {
            "a limit is 1 to $MAX_SENDS_PER_MINUTE sends a minute and a burst of 1 to $MAX_SEND_BURST"
        }
}

/** Each peer's own send limit, read from its limit.json once and kept; the default for a peer that has none. */
public class PeerLimits(private val store: PeerStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val held = ConcurrentHashMap<String, PeerLimit>()

    /** [name]'s limit: its own, or the default when it has none or its file cannot be read. */
    public fun of(name: String): PeerLimit = held.computeIfAbsent(name) { read(it) ?: PeerLimit() }

    /** Gives [name] [limit] as its own, or the default again when [limit] is null. False when there is no such peer. */
    public fun set(name: String, limit: PeerLimit?): Boolean {
        if (store.get(name) == null) return false
        val file = store.dir(name).resolve(LIMIT_FILE)
        if (limit == null) {
            Files.deleteIfExists(file)
        } else {
            SecureFile.writeAtomic0600(file, json.encodeToString(PeerLimit.serializer(), limit) + "\n")
        }
        held[name] = limit ?: PeerLimit()
        return true
    }

    /** Forgets what was read of [name], on revoke. */
    public fun forget(name: String) {
        held.remove(name)
    }

    private fun read(name: String): PeerLimit? {
        val file = store.dir(name).resolve(LIMIT_FILE)
        if (!Files.isRegularFile(file)) return null
        return Cancellables.runCatchingBestEffort {
            json.decodeFromString(PeerLimit.serializer(), Files.readString(file))
        }.getOrNull()?.takeIf { it.refusal() == null }
    }
}
