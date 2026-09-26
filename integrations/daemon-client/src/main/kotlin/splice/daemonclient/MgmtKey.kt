// NEW: the management key as a CLI process reads it (DR-174), moved together from doctor's
// DoctorCheckTypes (the result) and AdminSupport.readMgmtKey (the read) for LAYOUT-01: every verb
// that asks the daemon anything presents this key, so it lives with the daemon client.
package splice.daemonclient

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyStatePaths
import java.nio.file.Files

/** DR-174: the mgmt-key file as a reader actually found it — the same three-way distinction
 *  DoctorTopology already draws for splice.toml, applied to the credential beside it.
 *
 *  AdminSupport.mgmtKey returned String? through runCatching(...).getOrNull(), so a key sitting at
 *  0000 and a key that was never minted arrived identically as null. `splice restart` rendered that
 *  single null as "mgmt-key not found at <path> — can't stop the daemon", which is advice to go
 *  create a key that already exists, when the actual remedy is one chmod. DoctorHeadChecks.mgmtKeyCheck
 *  has drawn this distinction correctly since DR-41a and says so out loud — "it may exist, so nothing
 *  needs re-minting" — so the law was established in-repo and the other readers simply never received
 *  it. Only NoSuchFileException is positive evidence of absence; every other read failure means the
 *  key MAY exist, and the operator must never be told to re-mint on that evidence.
 *
 *  Unreadable carries a rendered reason, never raw bytes: this is a credential path, so the text
 *  goes through SafeFailureText (DR-65). */
public sealed class MgmtKeyRead {
    public data class Present(public val key: String) : MgmtKeyRead()
    public data object Absent : MgmtKeyRead()
    public data class Unreadable(public val reason: String) : MgmtKeyRead()
}

/** Reads the management key the daemon minted into the state dir. */
public class MgmtKeyFile {

    /** DR-174: the mgmt-key read, with absence and denied access kept apart.
     *
     *  This replaced `mgmtKey(): String?`, which collapsed both into null — every caller then had to
     *  invent a sentence for a state it could not distinguish, and all three invented the wrong one.
     *  Returning the distinction rather than a nullable is what stops the next caller re-deriving it;
     *  the old accessor is gone rather than kept beside this one, so there is no longer a shape that
     *  can silently lose the difference.
     *
     *  Mirrors DoctorHeadChecks.mgmtKeyCheck (DR-41a): a definitive NoSuchFileException is the only
     *  positive evidence of absence, and an empty file is treated as absent because MgmtKey.ensure
     *  writes the key and the path exists only once minted — a zero-byte file is a half-written
     *  mint, not a permissions problem. */
    public fun read(envReader: EnvReader): MgmtKeyRead {
        // V4-109: the daemon mints the key under its RESOLVED state dir, so a CLI reader resolves it
        // the same way; StatePaths(envReader) alone ignored [daemon].state_dir.
        val path = TopologyStatePaths(envReader).current().mgmtKeyFile
        val attempt = Cancellables.runCatchingCancellable { Files.readString(path).trim() }
        val failure = attempt.exceptionOrNull()
        if (failure != null) {
            return if (failure is java.nio.file.NoSuchFileException &&
                !Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            ) {
                MgmtKeyRead.Absent
            } else {
                MgmtKeyRead.Unreadable(SafeFailureText.render(failure))
            }
        }
        // Every failure returned above, so this is a success — no default to invent.
        val key = attempt.getOrThrow()
        return if (key.isEmpty()) MgmtKeyRead.Absent else MgmtKeyRead.Present(key)
    }
}
