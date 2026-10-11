// NEW: credential-file identity/presence observation, split from AccountCredentialEligibility
// so that file stays out of HIGH (V4-21 neighbourhood denom).
package splice.upstream.credentials

import splice.core.auth.CredentialFileDigest
import splice.core.auth.CredentialFileIdentity
import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Reads persisted credential evidence without exposing credential material to account policy. */
public fun interface AccountCredentialIdentitySource {
    /** Null means the revision could not be observed; [credentialPresence] classifies why. */
    public fun credentialIdentity(): CredentialFileIdentity?

    /** Conservative evidence when no revision was observable. Existing implementations remain unknown. */
    public fun credentialPresence(): CredentialPresence = CredentialPresence.UNKNOWN

    /** One typed observation for account reconciliation. File-backed providers override this directly. */
    public fun credentialEvidence(): CredentialEvidence {
        val identity = credentialIdentity()
        val presence = if (identity == null) credentialPresence() else CredentialPresence.PRESENT
        return CredentialEvidence(identity, presence)
    }

    public enum class CredentialPresence {
        PRESENT,
        MISSING,
        UNKNOWN,
    }

    public data class CredentialEvidence(
        public val identity: CredentialFileIdentity?,
        public val presence: CredentialPresence,
    ) {
        init {
            require(identity == null || presence == CredentialPresence.PRESENT) {
                "credential identity requires present evidence"
            }
        }
    }

    /** Quietly reads one conservative credential-file observation for account reconciliation. */
    public object CredentialFileEvidenceReader {
        public fun read(path: Path): CredentialEvidence =
            Cancellables.runCatchingCancellable {
                val attributes = Files.readAttributes(path, "basic:isRegularFile,lastModifiedTime,size")
                if (attributes["isRegularFile"] != true) return@runCatchingCancellable unknown()
                val modifiedAt = (attributes.getValue("lastModifiedTime") as FileTime).toMillis()
                val size = attributes.getValue("size") as Long
                // V4-70: the content digest rides the SAME best-effort block as the stat, so a read
                // failure collapses to the null identity below exactly as a stat failure does —
                // unknown, never "unchanged". Metadata alone could not tell a same-length rewrite
                // from the file the latch was armed against. See CredentialFileIdentity's header.
                CredentialEvidence(
                    CredentialFileIdentity(modifiedAt, size, CredentialFileDigest.of(path)),
                    CredentialPresence.PRESENT,
                )
            }.getOrElse { failure -> CredentialEvidence(null, presence(path, failure)) }

        private fun presence(path: Path, failure: Throwable): CredentialPresence =
            if (failure is NoSuchFileException && entryMissing(path)) {
                CredentialPresence.MISSING
            } else {
                CredentialPresence.UNKNOWN
            }

        private fun entryMissing(path: Path): Boolean =
            Cancellables.runCatchingCancellable { Files.notExists(path, LinkOption.NOFOLLOW_LINKS) }
                .fold(onSuccess = { it }, onFailure = { false })

        private fun unknown(): CredentialEvidence = CredentialEvidence(null, CredentialPresence.UNKNOWN)
    }
}
