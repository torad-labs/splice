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

public object SecureFile {
    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

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
            Files.createTempFile(parent, ".secure", ".tmp")
        }
        runCatchingCancellable {
            Files.writeString(tmp, content)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            runCatching { Files.deleteIfExists(tmp) }
            throw it
        }
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
    }
}
