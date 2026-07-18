// NEW: the two OS-touching primitives shared by every login flow (browser OAuth + device flow):
// openBrowser (best-effort, loopback-safe) and writeCredentialFile (atomic 0600 write, no
// world-readable window). Extracted verbatim from OAuthLoginFlow so DeviceLoginFlow reuses the
// exact same secure-write pattern instead of re-deriving it. :app is wall-exempt.
package splice.app

import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Best-effort open of a URL in the operator's default browser; false when unsupported/failed. */
internal fun openBrowser(url: String): Boolean = runCatchingCancellable {
    val os = System.getProperty("os.name").lowercase()
    val cmd = when {
        os.contains("mac") -> listOf("open", url)
        os.contains("nux") || os.contains("nix") -> listOf("xdg-open", url)
        else -> return false
    }
    ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    true
}.getOrDefault(false)

// Write credentials with owner-only perms from the instant the file exists — no world-readable
// window (the old write-then-chmod left a 0644 gap, and swallowed a failed chmod). Temp file at
// 0600 + ATOMIC_MOVE onto the target; falls back gracefully on a non-POSIX filesystem.
internal fun writeCredentialFile(path: Path, content: String) {
    val parent = path.parent
    Files.createDirectories(parent)
    val perms = PosixFilePermissions.fromString("rw-------")
    val tmp = try {
        Files.createTempFile(parent, ".auth", ".tmp", PosixFilePermissions.asFileAttribute(perms))
    } catch (_: UnsupportedOperationException) {
        Files.createTempFile(parent, ".auth", ".tmp")
    }
    runCatchingCancellable {
        Files.writeString(tmp, content)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onFailure {
        runCatching { Files.deleteIfExists(tmp) }
        throw it
    }
    runCatching { Files.setPosixFilePermissions(path, perms) }
}
