// NEW (Nov 12, 2026): a login's hold after its provider refused it survives a restart.
//
// The hold lived in the daemon's memory alone, so each restart forgot it and the next turns went to the refused login
// again. On Nov 11, 2026 a login whose organization had blocked it was refused four to six times after each of that
// day's restarts, and its card read as one a turn could take until the first of those answers came back. What a
// provider says about rate limits and plan windows already survives a restart (ProviderHoldStore); this keeps the
// login's side of it, in a file beside that one (ProviderHoldStore.loginHold).
package splice.upstream.retry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.auth.CredentialFileIdentity
import splice.core.failure.AccountBlock
import splice.core.failure.OwnerStep
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** A login's hold as it is kept between restarts: until when, after how many refusals in a row, the block its provider
 *  put on the account with the account the credential named then ([block], [account]), and the credential file the
 *  hold was put on ([identity]). A start puts it back on that account, or on that file where no block names one. */
public data class SavedLoginHold(
    val untilEpochMillis: Long,
    val failureCount: Int,
    val block: AccountBlock? = null,
    val account: String? = null,
    val identity: CredentialFileIdentity? = null,
)

/** Where one login's [SavedLoginHold] lives between restarts. */
public interface LoginHoldStore {
    /** The kept hold, or null when none is kept or it cannot be read. */
    public fun load(): SavedLoginHold?

    /** Replaces the kept hold; null removes it. */
    public fun save(hold: SavedLoginHold?)
}

/** [LoginHoldStore] over one small JSON file, 0600 and written atomically like every state file. A file that cannot be
 *  read loads as none, and the provider says again. The log names the failure's class, never the file or what it
 *  holds. */
public class FileLoginHoldStore(private val path: Path, private val log: LogSink) : LoginHoldStore {
    override fun load(): SavedLoginHold? {
        if (!Files.exists(path)) return null
        return Cancellables.runCatchingCancellable {
            parse(Json.parseToJsonElement(Files.readString(path)).jsonObject)
        }.onFailure {
            log("[login-hold] ignoring an unreadable hold (${it::class.simpleName}); the provider will say again\n")
        }.getOrNull()
    }

    override fun save(hold: SavedLoginHold?) {
        Cancellables.runCatchingCancellable {
            if (hold == null) {
                Files.deleteIfExists(path)
            } else {
                SecureFile.writeAtomic0600(path, json(hold))
            }
        }.onFailure {
            log("[login-hold] could not write a hold (${it::class.simpleName}); it lasts until restart\n")
        }
    }

    private fun parse(root: JsonObject): SavedLoginHold? {
        val until = JsonScalars.long(root, UNTIL) ?: return null
        return SavedLoginHold(
            untilEpochMillis = until,
            failureCount = JsonScalars.int(root, FAILURES) ?: 1,
            block = block(root[BLOCK] as? JsonObject),
            account = JsonScalars.str(root, BLOCKED_ACCOUNT),
            identity = identity(root[CREDENTIAL] as? JsonObject),
        )
    }

    /** A step this build does not know (a file a later build wrote) reads as no block: the hold then stands on its
     *  credential file alone. */
    private fun block(obj: JsonObject?): AccountBlock? {
        val name = JsonScalars.str(obj, STEP)
        val step = OwnerStep.entries.firstOrNull { it.name == name } ?: return null
        return JsonScalars.str(obj, WHERE)?.let { AccountBlock(step, it) }
    }

    private fun identity(obj: JsonObject?): CredentialFileIdentity? {
        val digest = JsonScalars.str(obj, DIGEST) ?: return null
        val changed = JsonScalars.long(obj, MTIME) ?: return null
        return JsonScalars.long(obj, SIZE)?.let { CredentialFileIdentity(changed, it, digest) }
    }

    private fun json(hold: SavedLoginHold): String = JsonWire.string(
        buildJsonObject {
            put(UNTIL, hold.untilEpochMillis)
            put(FAILURES, hold.failureCount)
            hold.block?.let { block ->
                putJsonObject(BLOCK) {
                    put(STEP, block.step.name)
                    put(WHERE, block.where)
                }
            }
            hold.account?.let { put(BLOCKED_ACCOUNT, it) }
            hold.identity?.let { file ->
                putJsonObject(CREDENTIAL) {
                    put(MTIME, file.mtimeMs)
                    put(SIZE, file.sizeBytes)
                    put(DIGEST, file.contentDigest)
                }
            }
        },
    )
}

private const val UNTIL = "until_epoch_ms"
private const val FAILURES = "refusals_in_a_row"
private const val BLOCK = "block"
private const val STEP = "step"
private const val WHERE = "where"
private const val BLOCKED_ACCOUNT = "account"
private const val CREDENTIAL = "credential_file"
private const val MTIME = "mtime_ms"
private const val SIZE = "size_bytes"
private const val DIGEST = "digest"
