// NEW: the one Muse minted-key writer. Login-end mint and MuseAuthProvider.persistGranted
// share this so a login cannot persist a different keep-list than refresh.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.auth.CredentialJson
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** Runs immediately before the atomic credential write; the provider supplies ensureActive. */
public fun interface MuseMintWriteGuard {
    public fun beforeWrite()
}

public class MuseMintPersistence {
    public fun persistGranted(
        authPath: Path,
        expectedAccessToken: String,
        key: MuseSubscriptionKey,
        log: LogSink,
        writeGuard: MuseMintWriteGuard = MuseMintWriteGuard { },
    ): Boolean {
        val current = readObject(authPath, log) ?: return false
        val access = JsonScalars.strIfString(current["access_token"])
        if (access != expectedAccessToken) {
            log("[muse-auth] credential changed while key mint was in flight — minted key discarded")
            return false
        }
        val replacements = buildJsonObject {
            key.fields.forEach { (name, value) ->
                if (name in persistedMintFields) put(name, value)
            }
            put("api_key", JsonPrimitive(key.apiKey))
            put("access_token", JsonPrimitive(expectedAccessToken))
        }
        val persisted = Cancellables.runCatchingCancellable {
            val merged = CredentialJson.mergedCredentialJson(current, replacements)
            writeGuard.beforeWrite()
            SecureFile.writeAtomic0600(authPath, merged.toString())
        }
        return persisted.fold(
            onSuccess = { true },
            onFailure = {
                log("[muse-auth] failed to persist the minted key; existing credential retained")
                false
            },
        )
    }

    private fun readObject(authPath: Path, log: LogSink): JsonObject? =
        Cancellables.runCatchingCancellable {
            museJson.parseToJsonElement(Files.readString(authPath))
        }.fold(
            onSuccess = { element ->
                (element as? JsonObject) ?: run {
                    log("[muse-auth] credential root is not an object; minted key discarded, existing credential kept")
                    null
                }
            },
            onFailure = {
                log("[muse-auth] credential file read failed; no credentials served")
                null
            },
        )

    private val persistedMintFields = setOf(
        "api_key",
        "access_token",
        "is_subs_active",
        "require_payment",
        "action_url",
        "require_payment_action_url",
        "subs_tier_id",
        "subs_tier_name",
        "subs_usage",
    )
}
