// NEW: server mode (docs/specs/server-mode.md, F1, F2 and decision 4). The keys a server answers: each bound to one
// person, minted by the operator or by a sign-in, listed without its secret, and revoked one at a time or for a
// whole person on their last day. A key a sign-in minted is bound to the device that signed in too: a newer sign-in
// on that device leaves the older key working until the newer one is first used, so a session still holding the
// older key is never cut off mid-turn, and the older key is then superseded; another device's keys are never touched.
// The daemon holds each key's hash and nothing else; a revoke replaces the map the listener reads before it returns, so the key fails on the very next request. When a key was last used is kept in
// memory as it happens and written with the next change, or once a minute, so a turn never waits on the disk.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.share.StateFile
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** One key as the server keeps and lists it: its [hash], never the key. Times are epoch milliseconds. */
@Serializable
public data class ServerKey(
    val prefix: String,
    val hash: String,
    val person: String,
    val name: String? = null,
    val source: String = OPERATOR_SOURCE,
    /** The device a sign-in minted this key for, as the laptop names itself; null for an operator's key. */
    val device: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    /** The prefix of the newer sign-in key on the same device whose first use revoked this one. */
    @SerialName("superseded_by") val supersededBy: String? = null,
)

@Serializable
internal data class KeyFile(val keys: List<ServerKey> = emptyList())

/** What a mint asks for: the [person] (a lowercased email), a [name] for the key, how long it lives, and for a key a
 *  sign-in mints, the [device] it is for. */
public data class KeyRequest(
    val person: String,
    val name: String? = null,
    val lifetimeMs: Long? = null,
    val source: String = OPERATOR_SOURCE,
    val device: String? = null,
)

/** A key just minted, and [row], what the server keeps of it. The key is held out of sight, so nothing that prints or
 *  logs this object shows it: only [reveal] gives it, for the one answer that shows it to its person. */
public class MintedKey(private val key: String, public val row: ServerKey) {
    public fun reveal(): String = key
}

/** What presenting a key came to. */
public sealed class KeyVerdict {
    public data class Admitted(val key: ServerKey) : KeyVerdict()

    /** No key was sent, or not one this server issued. */
    public data object Unknown : KeyVerdict()

    public data class Revoked(val key: ServerKey) : KeyVerdict()

    public data class Expired(val key: ServerKey) : KeyVerdict()
}

public class ServerKeys(
    /** `<state>/server/keys.json`. Read here, once; a file that is there and cannot be read throws. */
    file: Path,
    /** The file is written here, never on the thread a request arrived on. */
    private val io: CoroutineDispatcher,
    private val clock: Clock = Clock.systemUTC(),
    random: SecureRandom = SecureRandom(),
) {
    private val store = StateFile(file, KeyFile.serializer())
    private val mint = KeyMint(random)
    private val mutation = Mutex()

    @Volatile
    private var byHash: Map<String, ServerKey> = store.read()?.keys.orEmpty().associateBy { it.hash }

    /** When each key last admitted a request since this store was read, by prefix. */
    private val used = ConcurrentHashMap<String, Long>()

    @Volatile
    private var useKeptAt = clock.millis()

    @Volatile
    private var useChanged = false

    /** Every key, oldest first, or only [person]'s: each with when it was last used, none with a secret. */
    public fun list(person: String? = null): List<ServerKey> = byHash.values
        .filter { person == null || it.person == person }
        .map(::withUse)
        .sortedWith(compareBy({ it.createdAt }, { it.prefix }))

    public suspend fun mint(request: KeyRequest): MintedKey = mutation.withLock {
        val prefix = mint.prefix(byHash.values.mapTo(HashSet()) { it.prefix })
        val key = mint.key(prefix)
        val now = clock.millis()
        val row = ServerKey(
            prefix = prefix,
            hash = mint.hash(key),
            person = request.person,
            name = request.name,
            source = request.source,
            device = request.device,
            createdAt = now,
            expiresAt = request.lifetimeMs?.let { now + it },
        )
        publish(byHash + (row.hash to row))
        MintedKey(key, row)
    }

    /** Revokes the key [prefix] names and answers it, as it now stands; null when no key has that prefix. A key
     *  already revoked keeps the moment it was first revoked. */
    public suspend fun revoke(prefix: String): ServerKey? = mutation.withLock {
        val key = byHash.values.firstOrNull { it.prefix == prefix } ?: return@withLock null
        if (key.revokedAt != null) return@withLock withUse(key)
        val revoked = key.copy(revokedAt = clock.millis())
        publish(byHash + (revoked.hash to revoked))
        withUse(revoked)
    }

    /** Revokes every live key of [person] and answers how many that was. */
    public suspend fun revokePerson(person: String): Int = mutation.withLock {
        val now = clock.millis()
        val live = byHash.values.filter { it.person == person && it.revokedAt == null }
        if (live.isNotEmpty()) publish(byHash + live.map { it.hash to it.copy(revokedAt = now) })
        live.size
    }

    /** What [presented] opens. It is hashed and the hash looked up, so the time this takes says nothing of a key. A
     *  sign-in key's first use supersedes the older live keys of its device before it answers, so the very next
     *  request on one of them is refused. */
    public suspend fun admit(presented: String?): KeyVerdict {
        val key = presented?.takeIf { it.startsWith(KEY_MARK) }?.let { byHash[mint.hash(it)] }
            ?: return KeyVerdict.Unknown
        val now = clock.millis()
        return when {
            key.revokedAt != null -> KeyVerdict.Revoked(key)
            key.expiresAt != null && now >= key.expiresAt -> KeyVerdict.Expired(key)
            else -> {
                if (key.lastUsedAt == null && !used.containsKey(key.prefix)) supersedeElders(key)
                used[key.prefix] = now
                useChanged = true
                KeyVerdict.Admitted(key)
            }
        }
    }

    /** Revokes the live sign-in keys of [newer]'s person and device that were minted before it, each marked as
     *  superseded by it. Nothing happens for an operator's key, or a device with no older key. */
    private suspend fun supersedeElders(newer: ServerKey) {
        val device = newer.device?.takeIf { newer.source == SIGNIN_SOURCE } ?: return
        val elder = { key: ServerKey ->
            key.source == SIGNIN_SOURCE && key.device == device && key.person == newer.person &&
                key.revokedAt == null && key.prefix != newer.prefix && key.createdAt <= newer.createdAt
        }
        if (byHash.values.none(elder)) return
        mutation.withLock {
            val now = clock.millis()
            val elders = byHash.values.filter(elder)
            if (elders.isNotEmpty()) {
                publish(byHash + elders.map { it.hash to it.copy(revokedAt = now, supersededBy = newer.prefix) })
            }
        }
    }

    /** Whether [text] holds a key this server issued, live or not, anywhere in it. What a caller sends beside the key
     *  that admitted it is asked this before any of it leaves the machine: a person's key stays at the server. Each
     *  run with a key's shape is hashed and looked up, as [admit] does, and nothing is marked used. */
    internal fun heldIn(text: String): Boolean = mint.shapedIn(text).any { mint.hash(it) in byHash }

    /** Writes when each key was last used, if any was used since the last write and that write is [olderThanMs] old.
     *  The listener asks after a turn; the daemon's stop asks with 0, so nothing is lost at a restart. */
    public suspend fun keepUse(olderThanMs: Long = USE_KEPT_EVERY_MS) {
        if (!useChanged || clock.millis() - useKeptAt < olderThanMs) return
        mutation.withLock { publish(byHash) }
    }

    /** [keys] become the keys the listener reads, once the file holds them: a write that fails changes nothing. */
    private suspend fun publish(keys: Map<String, ServerKey>) {
        useChanged = false
        val kept = keys.mapValues { (_, key) -> withUse(key) }
        withContext(io) { store.write(KeyFile(kept.values.sortedBy { it.createdAt })) }
        byHash = kept
        useKeptAt = clock.millis()
    }

    private fun withUse(key: ServerKey): ServerKey = used[key.prefix]?.let { key.copy(lastUsedAt = it) } ?: key
}

/** The source of a key the operator issued, from the CLI, the control plane or an admin's key. */
internal const val OPERATOR_SOURCE: String = "operator"

/** The source of a key a person's own sign-in minted (F2). */
internal const val SIGNIN_SOURCE: String = "signin"

// why: an operator reads "last used" to find the keys nobody uses, in days; a minute of it lost to a crash changes
// no such answer, and one write a minute is nothing to the disk.
private const val USE_KEPT_EVERY_MS = 60_000L
