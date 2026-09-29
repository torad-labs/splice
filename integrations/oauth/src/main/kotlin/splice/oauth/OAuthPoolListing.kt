// NEW: V4-405 — the pool entries that are listed but never loaded. discover() kept only regular credential
// files, so an account whose credential was deleted (its `<label>-quota.json` left behind) and a symlinked
// credential both vanished after a restart: Needs you never offered the renewal V4-357 wrote, and a refused
// link never reached the console. Split from OAuthAccountFiles for its function count.
package splice.oauth

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** A name splice never creates: a refused entry's [OAuthAccountFile.credentialFile], so a consumer that
 *  builds its credential reader from that path for every listed account reads nothing. */
private const val REFUSED_SUFFIX = ".json.refused"

internal class OAuthPoolListing(private val validation: OAuthAccountValidation) {
    /** A quota with no credential beside it is an account that needs its sign-in renewed, not a stray
     *  file: it lists with no credential, so the console offers the renewal. */
    fun orphanedQuota(label: String, poolDir: Path): OAuthAccountFile? {
        val quota = quota(poolDir, label)
        if (!usable(label) || !Files.exists(quota, LinkOption.NOFOLLOW_LINKS)) return null
        return OAuthAccountFile(label, poolDir.resolve("$label$JSON_SUFFIX"), quota, false, credentialPresent = false)
    }

    /** A linked credential is never loaded, so it lists as refused with the reason in words. Its
     *  credential path is not the link, which a reader would follow. */
    fun refusedLink(label: String, poolDir: Path): OAuthAccountFile? {
        if (!usable(label)) return null
        return OAuthAccountFile(
            label,
            poolDir.resolve("$label$REFUSED_SUFFIX"),
            quota(poolDir, label),
            false,
            credentialPresent = false,
            refusal = "'$label' is a symbolic link, and splice does not load a linked credential; " +
                "remove the link and sign in again, or sign in under a different label",
        )
    }

    private fun quota(poolDir: Path, label: String): Path = poolDir.resolve("$label-quota$JSON_SUFFIX")

    /** A name that cannot be an account label (unsafe, reserved, or ending `-quota`) is not an account. */
    private fun usable(label: String): Boolean =
        try {
            validation.requireLabel(label)
            true
        } catch (_: OAuthAccountRefused) {
            false
        }
}
