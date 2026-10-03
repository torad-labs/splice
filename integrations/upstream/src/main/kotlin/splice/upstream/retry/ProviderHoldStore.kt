// NEW: V4-412. What a provider said about when it stops refusing survives a restart. Two statements
// only, both WALL instants because a restart resets the elapsed clock they were measured on: the
// provider's reset (V4-47's capture, read by status and usage) and the plan window it named spent
// (V4-233). The fail-fast horizon is NOT here: it is splice's own follower protection, clamped at
// 120s, and restart stays its escape hatch (RateLimitCooldown's header, V4-47).
package splice.upstream.retry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.usage.PlanLimit
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.core.wire.RateLimitReply
import java.nio.file.Files
import java.nio.file.Path

/** The provider's word, as wall instants in epoch seconds; either half may be absent. */
public data class ProviderHold(val providerResetAtEpochSeconds: Long?, val plan: PlanLimit?) {
    public var rateLimitReply: RateLimitReply? = null
        internal set
    val isEmpty: Boolean get() = providerResetAtEpochSeconds == null && plan == null
}

/** Where a head's or pool account's [ProviderHold] lives between restarts. */
public interface ProviderHoldStore {
    /** The stored statement, or null when none is stored or it cannot be read. */
    public fun load(): ProviderHold?

    /** Replaces the stored statement; an empty one removes it. */
    public fun save(hold: ProviderHold)

    /** Isolates a proved credential. A store without a scoped implementation persists nothing for it. */
    public fun forCredential(key: String): ProviderHoldStore? = null

    /** Enumerates persisted credential identities for reporting before any caller arrives. */
    public fun credentialKeys(): List<String> = emptyList()
}

/** [ProviderHoldStore] over one small JSON file, 0600 like every state file, written atomically.
 *  A missing file is the normal first boot and says nothing; a file that cannot be read or parsed
 *  loads as none and logs the failure class, never the credential-keyed filename or file content. */
public class FileProviderHoldStore(
    private val path: Path,
    private val log: LogSink,
) : ProviderHoldStore {

    override fun forCredential(key: String): FileProviderHoldStore {
        require(credentialKey(key))
        val scoped = path.resolveSibling("${path.fileName.toString().removeSuffix(".json")}-$key.json")
        return FileProviderHoldStore(scoped, log)
    }

    private fun credentialKey(key: String): Boolean =
        key.length == CREDENTIAL_KEY_HEX_CHARS && key.all { it in '0'..'9' || it in 'a'..'f' }

    override fun credentialKeys(): List<String> {
        val directory = path.toAbsolutePath().parent
        if (!Files.isDirectory(directory)) return emptyList()
        val prefix = "${path.fileName.toString().removeSuffix(".json")}-"
        return Cancellables.runCatchingCancellable {
            Files.list(directory).use { entries ->
                entries.map { it.fileName.toString() }
                    .filter { it.startsWith(prefix) && it.endsWith(".json") }
                    .map { it.removePrefix(prefix).removeSuffix(".json") }
                    .filter(::credentialKey)
                    .toList()
            }
        }.onFailure {
            log(
                "[provider-hold] hold census failed (${it::class.simpleName}); callers restore their own\n",
            )
        }.getOrDefault(emptyList())
    }

    override fun load(): ProviderHold? {
        if (!Files.exists(path)) return null
        return Cancellables.runCatchingCancellable {
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            ProviderHold(JsonScalars.long(root, RESET_AT), plan(root[PLAN] as? JsonObject)).also { hold ->
                root[REPLY]?.let { hold.rateLimitReply = Json.decodeFromJsonElement(RateLimitReply.serializer(), it) }
            }
        }.onFailure {
            log("[provider-hold] ignoring unreadable hold (${it::class.simpleName}); the provider will say again\n")
        }.getOrNull()
    }

    override fun save(hold: ProviderHold) {
        Cancellables.runCatchingCancellable {
            if (hold.isEmpty) {
                Files.deleteIfExists(path)
            } else {
                SecureFile.writeAtomic0600(path, json(hold))
            }
        }.onFailure {
            log("[provider-hold] could not write hold (${it::class.simpleName}); the hold lasts until restart\n")
        }
    }

    private fun plan(obj: JsonObject?): PlanLimit? {
        val claim = JsonScalars.str(obj, CLAIM) ?: return null
        return JsonScalars.long(obj, RESET_AT)?.let { PlanLimit(claim, it) }
    }

    private fun json(hold: ProviderHold): String = buildJsonObject {
        hold.providerResetAtEpochSeconds?.let { put(RESET_AT, it) }
        hold.rateLimitReply?.let { put(REPLY, Json.encodeToJsonElement(RateLimitReply.serializer(), it)) }
        hold.plan?.let { limit ->
            putJsonObject(PLAN) {
                put(CLAIM, limit.claim)
                put(RESET_AT, limit.resetEpochSeconds)
            }
        }
    }.toString()
}

private const val RESET_AT = "reset_at_epoch_seconds"
private const val PLAN = "plan"
private const val CLAIM = "claim"
private const val REPLY = "rate_limit_reply"

// why: SHA-256 has 32 digest bytes and two hexadecimal characters per byte.
private const val CREDENTIAL_KEY_HEX_CHARS = 64

/** The two halves of one [ProviderHold], held together so either can change and the file always
 *  carries both. [RateLimitCooldown] writes the provider's reset and [PlanHold] the plan window;
 *  neither knows the other or the file. With no store it only remembers, as before V4-412. */
internal class ProviderHolds(private val store: ProviderHoldStore?) {
    private var resetAt: Long? = null
    private var plan: PlanLimit? = null
    private var reply: RateLimitReply? = null

    var rateLimitReply: RateLimitReply?
        get() = reply

        @Synchronized
        set(value) {
            reply = value
            store?.save(snapshot())
        }

    private fun snapshot(): ProviderHold = ProviderHold(resetAt, plan).also { it.rateLimitReply = rateLimitReply }

    /** What was stored, read once at construction; consumers keep only what has not passed. */
    fun stored(): ProviderHold = store?.load() ?: ProviderHold(null, null)

    /** Records the provider's reset (null when it ended) beside the plan window and writes both. */
    @Synchronized
    fun providerReset(epochSeconds: Long?) {
        if (resetAt == epochSeconds) return
        resetAt = epochSeconds
        store?.save(snapshot())
    }

    /** Records the plan window (null when it ended) beside the provider's reset and writes both. */
    @Synchronized
    fun plan(limit: PlanLimit?) {
        if (plan == limit) return
        plan = limit
        store?.save(snapshot())
    }

    /** Seeds what a restart [kept] of what was [stored]; a statement that has passed is dropped from
     *  the file too, so it is not read again. */
    @Synchronized
    fun restored(stored: ProviderHold, kept: ProviderHold) {
        resetAt = kept.providerResetAtEpochSeconds
        plan = kept.plan
        reply = stored.rateLimitReply.takeUnless { kept.isEmpty }
        if (kept != stored || reply != stored.rateLimitReply) store?.save(snapshot())
    }
}
