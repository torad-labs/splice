// NEW: Muse credential-file snapshots and stable, classified reads.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialFileIdentity
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.InvalidGrantLatch
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.upstream.credentials.AccountCredentialIdentitySource.CredentialPresence
import splice.upstream.credentials.CredentialText
import java.nio.file.Files
import java.nio.file.Path

internal data class MuseCredentialSnapshot(
    val accessToken: String?,
    val apiKey: String?,
    val fields: JsonObject,
    val identity: CredentialFileIdentity?,
) {
    /** [fields] is the retained credential-file body, so it is secret WHOLE rather than field by
     *  field — its size is the only part kept, because "how much was parsed" is the diagnostic. */
    override fun toString(): String =
        "MuseCredentialSnapshot(accessToken=${held(accessToken)}, apiKey=${held(apiKey)}, " +
            "fields=<redacted:${fields.size} key(s)>, identity=$identity)"

    /** Whether this snapshot is the credential revision a verdict was recorded for: the same file state where both are
     *  known, else the same account token. */
    fun isRevision(identity: CredentialFileIdentity?, accessToken: String?): Boolean =
        if (identity != null && this.identity != null) identity == this.identity else accessToken == this.accessToken

    /** The key on file when a mint since replaced [refusedKey], the key a refused request sent; null when the file
     *  still holds that key, holds none, or no refused key is known. */
    fun keyReplacing(refusedKey: String?): String? = apiKey?.takeIf { refusedKey != null && it != refusedKey }

    /** `null` or `<redacted>` — never the value. */
    private fun held(value: String?): String = if (value == null) "null" else "<redacted>"
}

internal class MuseCredentialStore(
    private val authPath: Path,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    // Audit, Nov 9, 2026: every read takes the document a failed write of a minted key still holds, else the file.
    private val text: CredentialText = CredentialText { Files.readString(authPath) },
) {
    @Volatile private var cache: Cache? = null

    fun describe(holds: MuseMintHolds, poll: MusePollBackoff, invalidAccountLatch: InvalidGrantLatch): AuthDescription {
        val snapshot = read()
        return AuthDescription(
            present = snapshot?.apiKey != null,
            kind = "muse-oauth",
            fields = buildMap {
                put("login", "device")
                put("auth_path", authPath.toString())
                snapshot?.let { current ->
                    // A turn's verdict, which holds the login, is said over what the usage poll found.
                    putAll(poll.description(current))
                    putAll(holds.description(current))
                    if (invalidAccountLatch.isLatched(current.identity)) put("account_token", "invalid")
                }
            },
        )
    }

    fun observedCredentialKey(): String? = read()?.apiKey?.let {
        CredentialKey.fromCredentials(Credentials.Bearer(it))
    }

    fun clearCache() {
        cache = null
    }

    fun read(authCacheMs: Long = 0L): MuseCredentialSnapshot? {
        if (authCacheMs > 0L) {
            val identity = CredentialFileEvidenceReader.read(authPath).identity
            cache?.let { cached ->
                val fresh = cached.identity == identity && identity != null
                if (fresh && clock() - cached.loadedAt < authCacheMs) return cached.snapshot
            }
        }
        val snapshot = readFromDisk() ?: return null
        if (authCacheMs > 0L) cache = Cache(snapshot, snapshot.identity, clock())
        return snapshot
    }

    private fun readFromDisk(): MuseCredentialSnapshot? {
        val before = CredentialFileEvidenceReader.read(authPath)
        if (before.presence == CredentialPresence.MISSING) return null
        val decoded = Cancellables.runCatchingCancellable {
            val parsed = museJson.parseToJsonElement(text.read())
            requireNotNull(parsed as? JsonObject) { "Muse credential root must be an object" }
        }
        return decoded.fold(
            onSuccess = { fields -> stableSnapshot(before.identity, fields) },
            onFailure = {
                log("[muse-auth] credential file read failed; no credentials served")
                null
            },
        )
    }

    private fun stableSnapshot(
        before: CredentialFileIdentity?,
        fields: JsonObject,
    ): MuseCredentialSnapshot? {
        val after = CredentialFileEvidenceReader.read(authPath)
        if (before != after.identity) {
            log("[muse-auth] credential changed during read; retrying on the next probe")
            return null
        }
        return MuseCredentialSnapshot(
            accessToken = JsonScalars.strIfString(fields["access_token"]).takeIf(String::isNotBlank),
            apiKey = JsonScalars.strIfString(fields["api_key"]).takeIf(String::isNotBlank),
            fields = fields,
            identity = after.identity,
        )
    }
}

private data class Cache(
    val snapshot: MuseCredentialSnapshot,
    val identity: CredentialFileIdentity?,
    val loadedAt: Long,
)
