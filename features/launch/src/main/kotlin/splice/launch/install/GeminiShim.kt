// A profile that swaps Claude Code by name can select this shim for Antigravity. install --all, setup update and
// upgrade aftercare share one writer. The versioned companion is never resolved through the session's PATH.
package splice.launch.install

import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption
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

/** The Antigravity shim under [env]'s home, for [pipe] when it is installed. */
public class GeminiShim(
    private val env: EnvReader,
    private val pipe: Path = InstallPaths(envReader = env).desktopPipe,
) {
    /** Where the shim lives: `~/.local/lib/splice/gemini/claude`, as install.sh lays it. */
    public fun path(): Path = UserHome.dir(env).resolve(LIB_DIR).resolve(GEMINI_DIR).resolve(SHIM)

    /** Whether this version's shim is present with its intended content and mode. */
    public fun current(): Boolean =
        Cancellables.runCatchingBestEffort {
            Files.isRegularFile(path(), LinkOption.NOFOLLOW_LINKS) &&
                Files.getPosixFilePermissions(path()) == EXECUTABLE && Files.readString(path()) == text()
        }.getOrDefault(false)

    /** Lays the shim when it is missing or holds other bytes, atomically, 0755. Never throws. */
    public fun lay(): GeminiShimLaid {
        if (current()) return GeminiShimLaid.Current
        val shim = path()
        return Cancellables.runCatchingBestEffort {
            SecureFile.writeAtomic(shim, text(), EXECUTABLE)
            GeminiShimLaid.Written(shim)
        }.getOrElse { GeminiShimLaid.Failed(SafeFailureText.render(it)) }
    }

    private fun text(): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("pipe=${word(pipe)}")
        appendLine("if [ -f \"\$pipe\" ] && [ -x \"\$pipe\" ]; then exec \"\$pipe\" --acp \"\$@\"; fi")
        val layout = InstallLayout()
        appendLine("local_claude=${word(layout.localBin(env).resolve("claude"))}")
        appendLine("splice_launcher=${word(layout.launchShimPath(env))}")
        appendLine("wrap_state=${word(StatePaths(envReader = env).stateDir.resolve("claude-head-wrap.json"))}")
        append(checkNotNull(Thread.currentThread().contextClassLoader.getResource("gemini-fallback.sh")).readText())
    }

    private fun word(path: Path): String = "'" + path.toAbsolutePath().toString().replace("'", "'\\''") + "'"
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
