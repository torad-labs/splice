package splice.sessions.peer.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import splice.core.auth.BearerScheme
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerKeyRef
import splice.sessions.peer.PeerPrincipal
import splice.sessions.peer.PeerWebhook
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.CodexNames
import splice.sessions.peer.codex.CodexRequest
import splice.sessions.peer.codex.NativeProcess
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// why: 32 random bytes for the peer key (2.1), base64url without padding, which is 43 characters.
private const val PEER_KEY_BYTES = 32

// why: 32 random bytes as hex for the webhook HMAC secret (3.2).
private const val SECRET_BYTES = 32

private const val KEY_PREFIX = "splp_"

private const val PEERS_DIR = "peers"
internal const val PEER_JSON = "peer.json"
private const val PEER_KEY = "peer.key"
internal const val WEBHOOK_SECRET = "webhook.secret"
private const val WEBHOOK_BEARER = "webhook.bearer"
private const val FIRST_KEY_ID = "k1"

/** What a register asks for. [cwd] is absolute; [sendTo] is `["*"]` or names and session ids. */
public data class PeerRequest(
    val name: String,
    val cwd: String,
    val sendTo: List<String>,
    val owner: NativeProcess? = null,
)

/** Why a register was refused: a request that can never succeed, or a name that is reserved or taken (400 and 409). */
public enum class PeerRefusal { INVALID, TAKEN }

/** What a register did to the peer it answers: made it, brought the one there up to date, or found it as asked. */
public enum class RegisterOutcome { CREATED, UPDATED, SAME }

/** The answer to a register: the peer and its key file, or why not. */
public sealed class PeerRegistration {
    public data class Registered(
        val config: PeerConfig,
        val keyFile: Path,
        val outcome: RegisterOutcome = RegisterOutcome.CREATED,
    ) : PeerRegistration()

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
    private val codexPeers = CodexPeers()
    private val reregister = PeerRegisterAgain()
    private val mutation = Mutex()

    public val root: Path get() = folder.root

    /** Why `peers/` could not be read at boot, or null. The store then admits no peer key until a register. */
    public val loadFailure: String? get() = folder.loadFailure

    private val booted: List<PeerFile> = folder.load()

    @Volatile
    private var peers: Map<String, PeerConfig> = booted.associate { it.name to folder.config(it) }

    /** Each codex peer's thread, by the peer's name (docs/peers.md). */
    @Volatile
    private var codexes: Map<String, CodexBinding> =
        booted.mapNotNull { file -> codexPeers.binding(file)?.let { file.name to it } }.toMap()

    @Volatile
    private var owners: Map<String, NativeProcess> = booted.mapNotNull { file ->
        file.owner?.let { file.name to NativeProcess(it.pid, it.procStart, it.pidDomain) }
    }.toMap()

    @Volatile
    private var byKeyHash: Map<String, String> = peers.values.associate { it.keySha256 to it.name }

    /** When each codex peer last attached since this store loaded: a thread's end before it does not revoke it. */
    private val attachedAt = ConcurrentHashMap<String, Long>()

    /** Every registered peer, by name. */
    public fun list(): List<PeerConfig> = peers.values.sortedBy { it.name }

    public fun get(name: String): PeerConfig? = peers[name]

    /** `<settings dir>/peers/<name>/`, whether or not it exists. */
    public fun dir(name: String): Path = folder.dir(name)

    public fun keyFile(name: String): Path = folder.keyFile(name)

    /** Whether [name] matches the name pattern, or is a codex peer's; a path segment from a request is checked with
     *  this before use. */
    public fun wellFormed(name: String): Boolean = rules.wellFormed(name) || codexPeers.isPeer(name)

    /** The thread each codex peer stands for, by the peer's name. */
    public val codexThreads: Map<String, CodexBinding> get() = codexes

    /** A registered native owner, never the detached helper's own identity. Legacy peers have no inferred owner. */
    public val nativeOwners: Map<String, NativeProcess>
        get() = codexes.mapNotNull { (name, binding) -> binding.owner?.let { name to it } }.toMap() + owners

    /**
     * Registers [request]'s thread as the codex peer `codex-<thread>`, or brings the one there up to date: peer.key
     * and peer.json for a new one, peer.json alone for a changed one, and nothing when nothing changed.
     */
    public suspend fun attachCodex(request: CodexRequest): CodexAttach = mutation.withLock {
        val name = codexPeers.name(request)
        val key = keys.mint()
        val now = clock.millis()
        attachedAt[name] = now
        val fresh = PeerFileKey(FIRST_KEY_ID, keys.hash(key), rotatedAt = now)
        val keyFile = folder.keyFile(name)
        when (val plan = codexPeers.plan(request, folder.read(name), peers.keys, fresh)) {
            is CodexPlan.Refused -> CodexAttach.Refused(plan.why, plan.reason)
            is CodexPlan.Same -> CodexAttach.Attached(peers[name] ?: publish(plan.file), keyFile, fresh = false)
            is CodexPlan.Update -> {
                folder.write(plan.file)
                CodexAttach.Attached(publish(plan.file), keyFile, fresh = true)
            }
            is CodexPlan.Create -> {
                folder.create(plan.file, key)
                CodexAttach.Attached(publish(plan.file), keyFile, fresh = true)
            }
        }
    }

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
     * now, which a new peer may not take (2.1). A register that repeats one already there answers that peer (Oct 28,
     * 2026): see [PeerRegisterAgain].
     */
    public suspend fun register(request: PeerRequest, liveNames: Set<String>): PeerRegistration =
        mutation.withLock {
            val handNamed = peers.keys.filterNot(codexPeers::isPeer).toSet()
            rules.malformed(request)?.let { return@withLock it }
            val existing = folder.read(request.name)?.takeIf { request.name in handNamed }
            if (existing != null) {
                return@withLock when (val again = reregister.decide(existing, request)) {
                    is RegisterAgain.Refused -> again.answer
                    is RegisterAgain.Keep -> {
                        if (again.outcome == RegisterOutcome.UPDATED) folder.write(again.file)
                        PeerRegistration.Registered(publish(again.file), folder.keyFile(request.name), again.outcome)
                    }
                }
            }
            val refused = rules.taken(request, liveNames, handNamed, folder)
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
                owner = request.owner?.let { PeerFileOwner(it.pid, it.procStart, it.pidDomain) },
            )
            folder.create(file, key)
            PeerRegistration.Registered(publish(file), folder.keyFile(request.name))
        }

    /**
     * Revokes [name]: drops its hash first, so its key fails before anything else happens, then deletes
     * `<settings dir>/peers/<name>/`. False when there is no such peer. With [threadEndedAt], it revokes only a codex
     * peer whose thread ended then and that did not attach again since: a thread that loaded again keeps its peer.
     */
    public suspend fun revoke(
        name: String,
        threadEndedAt: Long? = null,
        expectedOwner: NativeProcess? = null,
    ): Boolean = mutation.withLock {
        val ended = threadEndedAt == null || (codexes[name] != null && (attachedAt[name] ?: 0L) <= threadEndedAt)
        val stillOwned = expectedOwner == null || nativeOwners[name] == expectedOwner
        if (name !in peers || !ended) return@withLock false
        if (!stillOwned) return@withLock false
        peers = peers - name
        codexes = codexes - name
        owners = owners - name
        attachedAt.remove(name)
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
        codexes = codexPeers.binding(file)?.let { codexes + (file.name to it) } ?: (codexes - file.name)
        owners = file.owner?.let { owners + (file.name to NativeProcess(it.pid, it.procStart, it.pidDomain)) }
            ?: (owners - file.name)
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
    private val codexNames = CodexNames()

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
        return names.filter { namePattern.matches(it) || codexNames.isPeer(it) }.mapNotNull { read(it) }
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
