// NEW: the `claude` Claude Desktop's launcher runs in place of Claude Code for an Antigravity model
// (docs/specs/desktop-antigravity.md): `~/.local/lib/splice/gemini/claude`, which runs `splice desktop acp` with
// Desktop's own arguments. install.sh step 4a' lays it; `splice install --all` and the upgrade's aftercare lay the same
// bytes, so an install that predates the shim, or one moved to another bin folder, gets it without a reinstall.
// Named `claude` so seatd gives the session the profile every Desktop session has; it names the splice it runs by path,
// since Desktop's PATH need not hold the bin folder.
package splice.launch.install

import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** What laying the shim did. */
public sealed class GeminiShimLaid {
    /** The shim already held these bytes. */
    public data object Current : GeminiShimLaid()

    /** The shim was written at [path]. */
    public data class Written(val path: Path) : GeminiShimLaid()

    /** The write failed for [why]; nothing else changed. */
    public data class Failed(val why: String) : GeminiShimLaid()
}

/** The Antigravity shim under [env]'s home, for the splice in [env]'s bin folder. */
public class GeminiShim(private val env: EnvReader) {
    /** Where the shim lives: `~/.local/lib/splice/gemini/claude`, as install.sh lays it. */
    public fun path(): Path = UserHome.dir(env).resolve(LIB_DIR).resolve(GEMINI_DIR).resolve(SHIM)

    /** Whether the shim is there and runs this install's splice. */
    public fun current(): Boolean =
        Cancellables.runCatchingBestEffort { Files.readString(path()) == text() }.getOrDefault(false)

    /** Lays the shim when it is missing or holds other bytes, atomically, 0755. Never throws. */
    public fun lay(): GeminiShimLaid {
        if (current()) return GeminiShimLaid.Current
        val shim = path()
        return Cancellables.runCatchingBestEffort {
            SecureFile.writeAtomic(shim, text(), EXECUTABLE)
            GeminiShimLaid.Written(shim)
        }.getOrElse { GeminiShimLaid.Failed(SafeFailureText.render(it)) }
    }

    /** install.sh's bytes: `exec '<bin>/splice' desktop acp "$@"`. */
    private fun text(): String {
        val splice = InstallPaths(envReader = env).binDir.resolve(SELF_COMMAND).toString().replace("'", "'\\''")
        return "#!/bin/sh\nexec '$splice' desktop acp \"\$@\"\n"
    }
}

private const val LIB_DIR = ".local/lib/splice"
private const val GEMINI_DIR = "gemini"
private const val SHIM = "claude"

// install.sh's chmod 0755.
private val EXECUTABLE = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_EXECUTE,
)
