package splice.sessions.peer.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.auth.BearerScheme
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerKeyRef
import splice.sessions.peer.PeerPrincipal
import splice.sessions.peer.PeerWebhook
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

// why: docs/specs/peer-messaging.md 2.1 caps a name at 32 characters, lowercase, starting with a letter, so it is a
// safe file name, a safe envelope attribute and a safe registry name.
private const val NAME_PATTERN = "^[a-z][a-z0-9-]{1,31}$"

// why: a sendTo entry is a name or a session id (a uuid); both fit this, and it keeps quotes and brackets out.
private const val SEND_TO_ENTRY_PATTERN = "^[A-Za-z0-9-]{1,64}$"

// why: the spec's peers-per-user limit (3.4); each peer is a resident JVM of about 48 MB.
private const val MAX_PEERS = 16

// why: 32 random bytes for the peer key (2.1), base64url without padding, which is 43 characters.
private const val PEER_KEY_BYTES = 32

// why: 32 random bytes as hex for the webhook HMAC secret (3.2).
private const val SECRET_BYTES = 32

private const val KEY_PREFIX = "splp_"
private const val ANY_TARGET = "*"

// why: peer.json's layout version (spec 2.1); a reader meeting a later one knows the file is not its shape.
private const val PEER_FILE_VERSION = 1

private const val PEERS_DIR = "peers"
internal const val PEER_JSON = "peer.json"
private const val PEER_KEY = "peer.key"
internal const val WEBHOOK_SECRET = "webhook.secret"
private const val WEBHOOK_BEARER = "webhook.bearer"
private const val FIRST_KEY_ID = "k1"

/** peer.json as it sits on disk (spec 2.1). */
@Serializable
internal data class PeerFile(
    val v: Int = PEER_FILE_VERSION,
    val name: String,
    val sessionId: String,
    val createdAt: Long,
    val cwd: String,
    val key: PeerFileKey,
    val scope: PeerFileScope,
    val webhook: PeerFileWebhook? = null,
)

@Serializable
internal data class PeerFileKey(val id: String, val sha256: String, val rotatedAt: Long)

@Serializable
internal data class PeerFileScope(val sendTo: List<String>)

@Serializable
internal data class PeerFileWebhook(
    val url: String,
    val enabled: Boolean,
    val secretFile: String,
    val bearerFile: String?,
)

/** What a register asks for. [cwd] is absolute; [sendTo] is `["*"]` or names and session ids. */
public data class PeerRequest(val name: String, val cwd: String, val sendTo: List<String>)

/** Why a register was refused: a request that can never succeed, or a name that is reserved or taken (400 and 409). */
public enum class PeerRefusal { INVALID, TAKEN }

/** The answer to a register: the new peer and its key file, or why not. */
public sealed class PeerRegistration {
    public data class Registered(val config: PeerConfig, val keyFile: Path) : PeerRegistration()

    public data class Refused(val why: PeerRefusal, val reason: String) : PeerRegistration()
}

/** The webhook files a set wrote; never their contents. */
public data class WebhookFiles(val secretFile: Path, val bearer: Boolean)

/**
 * The peers a user registered, under `<settings dir>/peers/<name>/` (spec 2 and 2.1): peer.json, the one copy of each
 * peer key, the webhook secret and bearer, the map from a key's sha256 to its peer that the PEER door admits by, the
 * names and the reserved list, and the send-scope check.
 *
 * Nothing is created until the first [register]: a missing `peers/` reads as an empty store. The daemon holds only
 * each key's sha256; the key is written to peer.key and dropped. A revoke or a rotate replaces the map before it
 * returns, so the old key fails from that moment.
 */
public class PeerStore(
    settingsDir: Path,
    random: SecureRandom = SecureRandom(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val folder = PeerFolder(settingsDir.resolve(PEERS_DIR))
    private val keys = PeerKeys(random)
    private val rules = PeerRules()
    private val mutation = Mutex()

    public val root: Path get() = folder.root

    /** Why `peers/` could not be read at boot, or null. The store then admits no peer key until a register. */
    public val loadFailure: String? get() = folder.loadFailure

    @Volatile
    private var peers: Map<String, PeerConfig> = folder.load().associate { it.name to folder.config(it) }

    @Volatile
    private var byKeyHash: Map<String, String> = peers.values.associate { it.keySha256 to it.name }

    /** Every registered peer, by name. */
    public fun list(): List<PeerConfig> = peers.values.sortedBy { it.name }

    public fun get(name: String): PeerConfig? = peers[name]

    /** `<settings dir>/peers/<name>/`, whether or not it exists. */
    public fun dir(name: String): Path = folder.dir(name)

    public fun keyFile(name: String): Path = folder.keyFile(name)

    /** Whether [name] matches the name pattern; a path segment from a request is checked with this before use. */
    public fun wellFormed(name: String): Boolean = rules.wellFormed(name)

    /**
     * The peer whose key is the bearer in [authorization], or null. The bearer is hashed first and the hash looked
     * up, so the time the lookup takes says nothing about the key.
     */
    public fun principal(authorization: String?): PeerPrincipal? {
        val bearer = BearerScheme.bearerToken(authorization)?.takeIf { it.startsWith(KEY_PREFIX) } ?: return null
        return byKeyHash[keys.hash(bearer)]?.let { PeerPrincipal(it) }
    }

    /** Whether [config]'s send scope admits a resolved target, by its name or its session id (spec 10, scope). */
    public fun mayReach(config: PeerConfig, targetName: String?, targetSessionId: String?): Boolean =
        ANY_TARGET in config.sendTo ||
            (targetName != null && targetName in config.sendTo) ||
            (targetSessionId != null && targetSessionId in config.sendTo)

    /**
     * Registers [request]'s peer: peer.key, then peer.json. [liveNames] are the names running registry records carry
     * now, which a new peer may not take (2.1).
     */
    public suspend fun register(request: PeerRequest, liveNames: Set<String>): PeerRegistration =
        mutation.withLock {
            val refused = rules.malformed(request) ?: rules.taken(request, liveNames, peers.keys, folder)
            if (refused != null) return@withLock refused
            val key = keys.mint()
            val now = clock.millis()
            val file = PeerFile(
                name = request.name,
                sessionId = UUID.randomUUID().toString(),
                createdAt = now,
                cwd = request.cwd,
                key = PeerFileKey(FIRST_KEY_ID, keys.hash(key), rotatedAt = now),
                scope = PeerFileScope(request.sendTo),
            )
            folder.create(file, key)
            PeerRegistration.Registered(publish(file), folder.keyFile(request.name))
        }

    /**
     * Revokes [name]: drops its hash first, so its key fails before anything else happens, then deletes
     * `<settings dir>/peers/<name>/`. False when there is no such peer.
     */
    public suspend fun revoke(name: String): Boolean = mutation.withLock {
        if (name !in peers) return@withLock false
        peers = peers - name
        byKeyHash = byKeyHash.filterValues { it != name }
        folder.deleteTree(folder.dir(name))
        true
    }

    /** Writes a new peer.key and replaces the hash; the old key fails at once. Null when there is no such peer. */
    public suspend fun rotate(name: String): Path? = mutation.withLock {
        val current = folder.read(name) ?: return@withLock null
        val key = keys.mint()
        val next = current.copy(
            key = PeerFileKey(id = keys.nextId(current.key.id), sha256 = keys.hash(key), rotatedAt = clock.millis()),
        )
        byKeyHash = byKeyHash.filterValues { it != name }
        folder.writeKey(name, key)
        folder.write(next)
        publish(next)
        folder.keyFile(name)
    }

    /**
     * Sets [name]'s webhook to [url], which the caller has already checked: mints a new webhook.secret, and writes
     * [bearer] to webhook.bearer when one is given, or removes an older one. Null when there is no such peer.
     */
    public suspend fun setWebhook(name: String, url: String, bearer: String?): WebhookFiles? = mutation.withLock {
        val current = folder.read(name) ?: return@withLock null
        val files = folder.writeWebhook(name, keys.secret(), bearer)
        val webhook = PeerFileWebhook(
            url = url,
            enabled = true,
            secretFile = WEBHOOK_SECRET,
            bearerFile = if (bearer != null) WEBHOOK_BEARER else null,
        )
        val next = current.copy(webhook = webhook)
        folder.write(next)
        publish(next)
        files
    }

    /** Clears [name]'s webhook and deletes its secret and bearer. False when there is no such peer. */
    public suspend fun clearWebhook(name: String): Boolean = mutation.withLock {
        val current = folder.read(name) ?: return@withLock false
        val next = current.copy(webhook = null)
        folder.write(next)
        folder.clearWebhook(name)
        publish(next)
        true
    }

    private fun publish(file: PeerFile): PeerConfig {
        val config = folder.config(file)
        peers = peers + (file.name to config)
        byKeyHash = byKeyHash.filterValues { it != file.name } + (config.keySha256 to file.name)
        return config
    }
}

/** The files under `peers/`: each peer's folder, its peer.json, its key and its webhook files. */
internal class PeerFolder(val root: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val namePattern = Regex(NAME_PATTERN)

    /** Why [load] could not list `peers/`, or null. */
    var loadFailure: String? = null
        private set

    fun dir(name: String): Path = root.resolve(name)

    fun keyFile(name: String): Path = dir(name).resolve(PEER_KEY)

    /** Writes a new peer's folder, key and peer.json. One that fails part way leaves nothing behind, so the name can
     *  be registered again. */
    fun create(file: PeerFile, key: String) {
        Cancellables.runCatchingCancellable {
            ownerOnly(root)
            ownerOnly(dir(file.name))
            writeKey(file.name, key)
            write(file)
        }.onFailure { failure ->
            deleteTree(dir(file.name))
            throw failure
        }
    }

    fun writeKey(name: String, key: String) {
        SecureFile.writeAtomic0600(keyFile(name), key + "\n")
    }

    fun write(file: PeerFile) {
        val text = json.encodeToString(PeerFile.serializer(), file) + "\n"
        SecureFile.writeAtomic0600(dir(file.name).resolve(PEER_JSON), text)
    }

    /** Writes webhook.secret and, when given, webhook.bearer; an older bearer is removed when none is given. */
    fun writeWebhook(name: String, secret: String, bearer: String?): WebhookFiles {
        val secretFile = dir(name).resolve(WEBHOOK_SECRET)
        val bearerFile = dir(name).resolve(WEBHOOK_BEARER)
        SecureFile.writeAtomic0600(secretFile, secret + "\n")
        if (bearer != null) {
            SecureFile.writeAtomic0600(bearerFile, bearer.trim() + "\n")
        } else {
            Files.deleteIfExists(bearerFile)
        }
        return WebhookFiles(secretFile, bearer != null)
    }

    fun clearWebhook(name: String) {
        Files.deleteIfExists(dir(name).resolve(WEBHOOK_SECRET))
        Files.deleteIfExists(dir(name).resolve(WEBHOOK_BEARER))
    }

    fun read(name: String): PeerFile? {
        val path = dir(name).resolve(PEER_JSON)
        if (!Files.isRegularFile(path)) return null
        return Cancellables.runCatchingBestEffort {
            json.decodeFromString(PeerFile.serializer(), Files.readString(path))
        }.getOrNull()?.takeIf { it.name == name }
    }

    /** Every readable peer.json under `peers/`; a missing folder is no peers, and an unreadable one sets [loadFailure]. */
    fun load(): List<PeerFile> {
        if (!Files.isDirectory(root)) return emptyList()
        val names = Cancellables.runCatchingBestEffort {
            Files.list(root).use { stream -> stream.map { it.fileName.toString() }.toList() }
        }.onFailure { loadFailure = "$root: ${SafeFailureText.render(it)}" }.getOrNull().orEmpty()
        return names.filter { namePattern.matches(it) }.mapNotNull { read(it) }
    }

    fun config(file: PeerFile): PeerConfig = PeerConfig(
        name = file.name,
        sessionId = file.sessionId,
        createdAt = file.createdAt,
        cwd = file.cwd,
        key = PeerKeyRef(id = file.key.id, sha256 = file.key.sha256),
        sendTo = file.scope.sendTo,
        webhook = file.webhook?.let { PeerWebhook(it.url, it.enabled, it.secretFile, it.bearerFile) },
    )

    fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        val paths = Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).toList() }
        paths.forEach { Files.deleteIfExists(it) }
    }

    private fun ownerOnly(dir: Path) {
        val open = SecureFile.ownerOnlyDirectory(dir)
        check(open == null) { "peer folder $dir is not owner-only: $open" }
    }
}

/** Peer keys and webhook secrets: minted from [random], and a key known to the daemon only by its sha256. */
internal class PeerKeys(private val random: SecureRandom) {
    fun mint(): String = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(PEER_KEY_BYTES))

    fun secret(): String = HexFormat.of().formatHex(bytes(SECRET_BYTES))

    fun hash(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))

    fun nextId(current: String): String = "k${(current.removePrefix("k").toIntOrNull() ?: 0) + 1}"

    private fun bytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}

/** What a register may ask for (2.1): the name, the cwd and the send scope, then whether the name is free. */
internal class PeerRules {
    private val namePattern = Regex(NAME_PATTERN)
    private val sendToPattern = Regex(SEND_TO_ENTRY_PATTERN)

    fun wellFormed(name: String): Boolean = namePattern.matches(name)

    /** Why [request] can never succeed, or null. */
    fun malformed(request: PeerRequest): PeerRegistration.Refused? {
        val cwd = Path.of(request.cwd)
        val reason = when {
            !namePattern.matches(request.name) -> "a peer name is a lowercase letter, then 1 to 31 of a-z, 0-9 or -"
            !cwd.isAbsolute || !Files.isDirectory(cwd) -> "cwd must be an absolute path to a directory"
            !validSendTo(request.sendTo) -> "sendTo is [\"*\"] or a list of names and session ids"
            else -> null
        }
        return reason?.let { PeerRegistration.Refused(PeerRefusal.INVALID, it) }
    }

    /** Why [request]'s name is not free: reserved, registered, a running session's, or over the peer limit. */
    fun taken(
        request: PeerRequest,
        liveNames: Set<String>,
        registered: Set<String>,
        folder: PeerFolder,
    ): PeerRegistration.Refused? {
        val name = request.name
        val reason = when {
            name in RESERVED.names -> "'$name' is reserved"
            name in registered || Files.exists(folder.dir(name)) -> "a peer named '$name' already exists"
            name in liveNames -> "a running session is named '$name'"
            registered.size >= MAX_PEERS -> "a user may have at most $MAX_PEERS peers"
            else -> null
        }
        return reason?.let { PeerRegistration.Refused(PeerRefusal.TAKEN, it) }
    }

    private fun validSendTo(sendTo: List<String>): Boolean =
        sendTo.isNotEmpty() && (sendTo == listOf(ANY_TARGET) || sendTo.all { sendToPattern.matches(it) })

    /** The names no peer may take (2.1): roles a reader could mistake for a person or for Splice itself. */
    private object RESERVED {
        val names: Set<String> = setOf(
            "system", "user", "operator", "human", "assistant", "claude", "anthropic", "splice", "splice-console",
            "telegram", "president", "marcos", "dispatch",
        )
    }
}
