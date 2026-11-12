// NEW: the one Muse minted-key writer. Login-end mint and MuseAuthProvider's mint share this so a login cannot persist
// a different keep-list than refresh.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.auth.CredentialJson
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.upstream.credentials.CredentialText
import splice.upstream.credentials.RetainedCredentialWrite
import java.nio.file.Files
import java.nio.file.Path

/** Runs immediately before the atomic credential write; the provider supplies ensureActive. */
public fun interface MuseMintWriteGuard {
    public fun beforeWrite()
}

public class MuseMintPersistence {
    /** The login's mint: the key is written to [authPath], or the login says it could not be stored. */
    public fun persistGranted(
        authPath: Path,
        expectedAccessToken: String,
        key: MuseSubscriptionKey,
        log: LogSink,
        writeGuard: MuseMintWriteGuard = MuseMintWriteGuard { },
    ): Boolean {
        val persisted = Cancellables.runCatchingCancellable {
            val merged = mergedDocument(CredentialText { Files.readString(authPath) }, expectedAccessToken, key, log)
                ?: return false
            writeGuard.beforeWrite()
            SecureFile.writeAtomic0600(authPath, merged)
        }
        return persisted.fold(
            onSuccess = { true },
            onFailure = {
                log("[muse-auth] failed to persist the minted key; existing credential retained")
                false
            },
        )
    }

    /** A turn's mint: the key is written through [retained], and a write that fails keeps it there, served in the
     *  file's place and written again at the next lookup or mint (audit, Nov 9, 2026, as codex, grok and kimi keep a
     *  renewed pair). Before, the minted key was dropped and every turn after it minted again on the dead stored key.
     *  False only when there is nothing to keep: the login changed under the mint, or its file cannot be read. */
    internal fun keepGranted(
        retained: RetainedCredentialWrite,
        expectedAccessToken: String,
        key: MuseSubscriptionKey,
        log: LogSink,
        writeGuard: MuseMintWriteGuard,
    ): Boolean {
        val merged = Cancellables.runCatchingCancellable { mergedDocument(retained, expectedAccessToken, key, log) }
            .fold(
                onSuccess = { it },
                onFailure = {
                    log("[muse-auth] the minted key could not be merged into the credential; existing credential kept")
                    null
                },
            ) ?: return false
        writeGuard.beforeWrite()
        retained.write(merged)?.let { log(retained.failedLine(it)) }
        return true
    }

    /** The credential document with the minted key merged in, or null when the login changed while the mint was in
     *  flight or the document cannot be read as an object. */
    private fun mergedDocument(
        current: CredentialText,
        expectedAccessToken: String,
        key: MuseSubscriptionKey,
        log: LogSink,
    ): String? {
        val document = readObject(current, log) ?: return null
        val access = JsonScalars.strIfString(document["access_token"])
        if (access != expectedAccessToken) {
            log("[muse-auth] credential changed while key mint was in flight; minted key discarded")
            return null
        }
        val replacements = buildJsonObject {
            key.fields.forEach { (name, value) ->
                if (name in persistedMintFields) put(name, value)
            }
            put("api_key", JsonPrimitive(key.apiKey))
            put("access_token", JsonPrimitive(expectedAccessToken))
        }
        return CredentialJson.mergedCredentialJson(document, replacements).toString()
    }

    private fun readObject(current: CredentialText, log: LogSink): JsonObject? =
        Cancellables.runCatchingCancellable {
            museJson.parseToJsonElement(current.read())
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
