// NEW: launch-shim copy, version-marker read, and staleness warning. Split from
// InstallCommand.kt (concentration HIGH, 2026-08-19). DoctorInstallProbes and
// Main still reach these through InstallCommand delegates.
package splice.app.cli

import splice.core.SHIM_VERSION
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// FILE SCOPE ON PURPOSE: one compiled Regex shared by every shim-version read, rather than a
// recompile per InstallCommand instance (doctor constructs one just to reach installedShimVersion).
private val SHIM_VERSION_LINE = Regex("""^SPLICE_SHIM_VERSION="([^"]*)"""", RegexOption.MULTILINE)

internal class InstallShim(
    private val layout: InstallLayout = InstallLayout(),
) {

    /** Copy the repo's launch shim into the share dir (used by install.sh / dev). */
    internal fun installShim(repoShim: Path, env: EnvReader) {
        Files.createDirectories(layout.shareDir(env))
        val dst = layout.launchShimPath(env)
        Files.copy(repoShim, dst, StandardCopyOption.REPLACE_EXISTING)
        check(dst.toFile().setExecutable(true)) { "failed to make launch shim executable: $dst" }
        println("splice: installed launch shim to $dst")
    }

    /** The SPLICE_SHIM_VERSION marker embedded in the installed shim, or null if none/unreadable. */
    internal fun installedShimVersion(env: EnvReader): String? {
        val shim = layout.launchShimPath(env)
        if (!Files.exists(shim)) return null
        return Cancellables.runCatchingCancellable {
            SHIM_VERSION_LINE.find(Files.readString(shim))?.groupValues?.get(1)
        }.getOrNull()
    }

    /** Non-fatal staleness message for the installed shim, or null when absent/current. */
    internal fun shimStalenessWarning(env: EnvReader): String? {
        val shim = layout.launchShimPath(env)
        if (!Files.exists(shim)) return null
        val installed = installedShimVersion(env)
        if (installed == SHIM_VERSION) return null
        return "splice: WARNING — installed launch shim at $shim is STALE " +
            "(marker=${installed ?: "<missing>"}, expected=$SHIM_VERSION). " +
            "Run: splice install (or ./install.sh) to refresh it."
    }
}
