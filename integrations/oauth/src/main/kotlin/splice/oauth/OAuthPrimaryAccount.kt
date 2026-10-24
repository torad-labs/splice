// NEW: Oct 10, 2026 — the first account of a ChatGPT, Grok, Kimi or Muse command can be renamed and removed like the
// accounts added after it (BUILD row "Accounts: rename or remove an account"). Its credential file stays where the
// command reads it. A rename stores a name only, the shape Claude's rename already has, because the first file can
// be the vendor CLI's own and splice never writes its own fields into that. A remove deletes the file only when it is
// splice's own; a file the provider config points at on purpose (auth.file) is shared, and keeps its refusal.
// CREDENTIAL-WRITE-EXEMPT[2026-10-10]: the one file written here is the first account's name, a one-line text file
// beside the pool; it holds no credential and no JSON, so there is nothing to merge onto.
package splice.oauth

import splice.core.config.UserHome
import splice.core.topology.AuthKind
import splice.core.util.SecureFile
import splice.upstream.credentials.AccountLabelPolicy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// why: the name sits in the command's pool folder beside its other accounts, under a name discovery never reads as one.
private const val NAME_FILE = "primary.name"

public class OAuthPrimaryAccount(private val files: OAuthAccountFiles = OAuthAccountFiles()) {

    /** The name a person gave [primaryFile]'s account, or null when it has none. */
    public fun name(kind: AuthKind.OAuth, primaryFile: Path): String? {
        val file = files.poolDir(kind, primaryFile).resolve(NAME_FILE)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.readString(file).trim().takeIf(AccountLabelPolicy::isSafe)
    }

    /** Names the first account [name]. The credential file is not touched. */
    public fun rename(kind: AuthKind.OAuth, primaryFile: Path, name: String) {
        if (!AccountLabelPolicy.isSafe(name)) throw OAuthAccountRefused("invalid OAuth account label")
        val dir = files.poolDir(kind, primaryFile)
        if (Files.exists(dir.resolve("$name$JSON_SUFFIX"), LinkOption.NOFOLLOW_LINKS)) {
            throw OAuthAccountRefused("an OAuth account is already labeled '$name'")
        }
        Files.createDirectories(dir)
        SecureFile.writeAtomic0600(dir.resolve(NAME_FILE), name)
    }

    /** Whether [primaryFile] is not splice's own default for [kind]: a file the provider config names on purpose,
     *  which the vendor's own CLI also reads. */
    public fun shared(kind: AuthKind.OAuth, primaryFile: Path): Boolean =
        kind.defaultAuthFile?.let { Path.of(UserHome.expand(it)).normalize() } != primaryFile.normalize()

    /** Deletes the first account's credential and its name, refused for a [shared] file. True when a credential
     *  existed to remove. */
    public fun remove(kind: AuthKind.OAuth, primaryFile: Path): Boolean {
        if (shared(kind, primaryFile)) {
            throw OAuthAccountRefused("this account's file is shared with the provider's own CLI; sign out there")
        }
        Files.deleteIfExists(files.poolDir(kind, primaryFile).resolve(NAME_FILE))
        return Files.deleteIfExists(primaryFile)
    }
}
