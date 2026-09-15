// NEW: Muse credential-file snapshots and stable, classified reads.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import splice.core.auth.CredentialFileIdentity
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.spi.AccountCredentialIdentitySource.CredentialFileEvidenceReader
import splice.spi.AccountCredentialIdentitySource.CredentialPresence
import java.nio.file.Files
import java.nio.file.Path

internal data class MuseCredentialSnapshot(
    val accessToken: String?,
    val apiKey: String?,
    val fields: JsonObject,
    val identity: CredentialFileIdentity?,
)

internal class MuseCredentialStore(
    private val authPath: Path,
    private val log: LogSink,
) {
    fun read(): MuseCredentialSnapshot? {
        val before = CredentialFileEvidenceReader.read(authPath)
        if (before.presence == CredentialPresence.MISSING) return null
        val decoded = Cancellables.runCatchingCancellable {
            val parsed = museJson.parseToJsonElement(Files.readString(authPath))
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
            log("[muse-auth] credential changed during read — retrying on the next probe")
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
