// NEW: launch-shim copy, version-marker read, and staleness warning. Split from
// InstallCommand.kt (concentration HIGH, 2026-08-19). DoctorInstallProbes and
// Main still reach these through InstallCommand delegates.
package splice.app.cli

import splice.core.SHIM_VERSION
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
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
    internal fun copyLaunchShim(repoShim: Path, env: EnvReader) {
        Files.createDirectories(layout.shareDir(env))
        val dst = layout.launchShimPath(env)
        Files.copy(repoShim, dst, StandardCopyOption.REPLACE_EXISTING)
        check(dst.toFile().setExecutable(true)) { "failed to make launch shim executable: $dst" }
        println("splice: installed launch shim to $dst")
    }

    /** The SPLICE_SHIM_VERSION marker, or null on PROVEN absence or a readable-but-unmarked
     *  shim. DR-69: indeterminate access THROWS — a present shim behind denied access is not
     *  "no marker", and both callers classify the failure loudly. */
    internal fun installedShimVersion(env: EnvReader): String? {
        val shim = layout.launchShimPath(env)
        return Cancellables.runCatchingCancellable {
            SHIM_VERSION_LINE.find(Files.readString(shim))?.groupValues?.get(1)
        }.getOrElse { failure ->
            val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
                !Files.exists(shim, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (genuinelyAbsent) null else throw failure
        }
    }

    /** Non-fatal staleness message for the installed shim, or null when absent/current. */
    internal fun shimStalenessWarning(env: EnvReader): String? {
        val shim = layout.launchShimPath(env)
        val installed = Cancellables.runCatchingCancellable { installedShimVersion(env) }
            .getOrElse { failure ->
                // DR-69: an unreadable shim previously read as "absent" and the warning went
                // missing exactly when the install was wedged. Never quotes file bytes.
                return "splice: WARNING — launch shim at $shim is UNREADABLE " +
                    "(${SafeFailureText.render(failure)}); its version cannot be verified. Fix access to $shim."
            }
        if (installed == null && !Files.exists(shim, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
        return if (installed == SHIM_VERSION) {
            null
        } else {
            "splice: WARNING — installed launch shim at $shim is STALE " +
                "(marker=${installed ?: "<missing>"}, expected=$SHIM_VERSION). " +
                "Run: splice install (or ./install.sh) to refresh it."
        }
    }
}
