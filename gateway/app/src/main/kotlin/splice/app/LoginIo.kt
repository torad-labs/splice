// NEW: the two OS-touching primitives shared by every login flow (browser OAuth + device flow):
// openBrowser (best-effort, loopback-safe) and writeCredentialFile (atomic 0600 write, no
// world-readable window). Extracted verbatim from OAuthLoginFlow so DeviceLoginFlow reuses the
// exact same secure-write pattern instead of re-deriving it. :app is wall-exempt.
package splice.app

import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Path

/** The shared login I/O primitives, held as a collaborator by each flow (Kotlin style law,
 *  2026-08-15): a helper used by several types is a small named class they construct, not a pair
 *  of free functions. */
internal class LoginIo {

    /** Best-effort open of a URL in the operator's default browser; false when unsupported/failed. */
    internal fun openBrowser(url: String): Boolean = Cancellables.runCatchingCancellable {
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

    // Write credentials atomically at 0600 — routes to the shared primitive. This file held the
    // canonical copy SecureFile was lifted from; delegating keeps a single source of truth.
    internal fun writeCredentialFile(path: Path, content: String) {
        SecureFile.writeAtomic0600(path, content)
    }
}
