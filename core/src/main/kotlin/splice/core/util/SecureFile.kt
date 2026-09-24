// NEW: the SINGLE secure-credential-write primitive (#924 Phase 3). Five modules each had their own
// "write a 0600 token file": four were atomic (temp → ATOMIC_MOVE), but the newest — KimiOAuth's
// writeSecure — was the vulnerable write-then-chmod that leaves the token WORLD-READABLE for a
// window and can tear under a concurrent reader. MgmtKey/Codex/Grok/LoginIo each re-derived the
// correct version AND wrote the same warning comment about the gap kimi still had. Extracting the
// correct primitive once and routing every credential write through it makes the regression class
// inexpressible: a token path can only be written the atomic-0600 way. core is framework-free
// (JDK-only here), so every provider + app can depend on this.
package splice.core.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

// v0.4.0 review round 2: every result here is a verdict the caller must act on (ownerOnlyDirectory's
// "why it is still open"); with RETURN_VALUE_NOT_USED an error, dropping one does not compile.
@MustUseReturnValues
public object SecureFile {
    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
    private val OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------")
    private val OPEN_TO_OTHERS = PosixFilePermissions.fromString("---rwxrwx")

    /**
     * V4-174: the DIRECTORY form of the same law, for a store whose every file carries private
     * content (a head's request/response trace). Owner-only (0700) from the instant the directory
     * exists, and re-asserted on every call, so a directory an operator recreated by hand, or that
     * a sweep emptied and a later write recreated, is never left at the umask's default. The files
     * inside need no mode of their own: a directory nobody else can traverse is the boundary.
     *
     * Returns null when the directory is owner-only afterwards, or when its filesystem keeps no
     * POSIX modes at all (nothing to hold); otherwise WHY it is still open, for the caller to say.
     * The answer is read off the mode the directory ends up with (v0.4.0 review): every chmod
     * failure used to be discarded as "unsupported", which a Linux filesystem never is, so a
     * refused chmod (a directory another account owns) left it open with nothing said, and a mount
     * that accepts a chmod and keeps its own bits says nothing even when the call succeeds.
     */
    public fun ownerOnlyDirectory(dir: Path): String? {
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR))
        } catch (_: UnsupportedOperationException) {
            Files.createDirectories(dir)
        }
        val held = try {
            Cancellables.runCatchingCancellable {
                Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR)
                Files.getPosixFilePermissions(dir)
            }
        } catch (_: UnsupportedOperationException) {
            // No POSIX modes on this filesystem: nothing to hold. Caught here, by name —
            // runCatchingCancellable passes it through, so a branch for it in getOrElse never ran
            // and the "null" this KDoc promises was a throw (v0.4.0 review round 2).
            return null
        }
        return held.fold(
            onSuccess = { mode ->
                val open = mode.any { it in OPEN_TO_OTHERS }
                if (open) "its mode stayed ${PosixFilePermissions.toString(mode)}" else null
            },
            onFailure = { failure -> SafeFailureText.render(failure) },
        )
    }

    /**
     * Write [content] to [path] with owner-only (0600) perms from the instant the file exists — no
     * world-readable window — and swap it in atomically, so a concurrent reader never observes a
     * torn or half-written credential. Parent dirs are created. On a non-POSIX filesystem the 0600
     * attribute is best-effort (the atomic move still holds).
     */
    public fun writeAtomic0600(path: Path, content: String) {
        val parent = path.parent
        Files.createDirectories(parent)
        val tmp = try {
            Files.createTempFile(parent, ".secure", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } catch (_: UnsupportedOperationException) {
            // IO-004: no POSIX attribute support at creation — the file briefly exists at default
            // (often world-readable) perms. Close the window before any content is written: set
            // owner-only perms first, best-effort (a non-POSIX filesystem has no perms to set, and
            // the atomic move afterward still holds), THEN write the credential.
            val fallback = Files.createTempFile(parent, ".secure", ".tmp")
            Cancellables.discard(
                runCatching { Files.setPosixFilePermissions(fallback, OWNER_ONLY) },
                "POSIX perms unsupported on this filesystem — nothing to lock down",
            )
            fallback
        }
        Cancellables.runCatchingCancellable {
            Files.writeString(tmp, content)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Cancellables.discard(
                runCatching { Files.deleteIfExists(tmp) },
                "tmp cleanup is best-effort; the write failure rethrows",
            )
            throw it
        }
        Cancellables.discard(
            runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) },
            "POSIX perms unsupported on this filesystem → keep the completed write",
        )
    }
}
