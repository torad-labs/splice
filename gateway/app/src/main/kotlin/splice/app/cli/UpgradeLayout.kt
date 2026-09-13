// NEW: v0.4.0 FEATURES.md §5 — where `splice upgrade` keeps releases and what it repoints. The
// layout is the one install.sh already writes (jar + shim under the share dir, wrappers in bin,
// config and credentials elsewhere — never touched here) plus one directory per release under
// <share>/releases/<version>/ holding the PRISTINE jar and launch shim, with `current` and
// `previous` links. The live jar becomes a symlink into the current release; the live shim stays a
// real file so a local edit (the hostshield launcher patch) survives — UpgradeWrapper decides.
package splice.app.cli

import splice.core.GATEWAY_VERSION
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal const val JAR_ASSET = "splice.jar"
internal const val SHIM_ASSET = "splice-launch"
internal const val UPGRADE_PAD = 11
private const val CURRENT_LINK = "current"
private const val PREVIOUS_LINK = "previous"

internal class UpgradeLayout(env: EnvReader, installLayout: InstallLayout = InstallLayout()) {
    val share: Path = installLayout.shareDir(env)
    val liveJar: Path = share.resolve(JAR_ASSET)
    val liveShim: Path = installLayout.launchShimPath(env)
    val releases: Path = share.resolve("releases")
    val current: Path = releases.resolve(CURRENT_LINK)
    val previous: Path = releases.resolve(PREVIOUS_LINK)

    fun versionDir(version: String): Path = releases.resolve(version)

    fun stagingDir(): Path = releases.resolve(".staging-${ProcessHandle.current().pid()}")

    /** The version the live jar points at: the current link when one exists, else this CLI's own. */
    fun installedVersion(): String = pointedVersion(current) ?: GATEWAY_VERSION

    fun pointedVersion(link: Path): String? =
        if (Files.isSymbolicLink(link)) Files.readSymbolicLink(link).fileName.toString() else null

    /** A flat install (install.sh before 0.4.0) has no release copy of what it runs; record the live
     *  jar under its version so rollback has somewhere to go. The shim is NOT copied: a live shim may
     *  carry a local edit, and only install.sh knows the pristine bytes. */
    fun ensureCurrentRecorded() {
        if (Files.isSymbolicLink(current)) return
        val dir = versionDir(installedVersion())
        Files.createDirectories(dir)
        if (!Files.exists(dir.resolve(JAR_ASSET))) {
            Files.copy(liveJar, dir.resolve(JAR_ASSET), StandardCopyOption.COPY_ATTRIBUTES)
        }
        point(current, dir.fileName)
    }

    /** Symlink swap: a sibling temp link, then ONE rename — the name never dangles, and a regular
     *  file at [link] (the flat install's jar) is replaced the same way. */
    fun point(link: Path, target: Path) {
        val tmp = link.resolveSibling(link.fileName.toString() + ".upgrade-tmp")
        Files.deleteIfExists(tmp)
        Files.createSymbolicLink(tmp, target)
        Files.move(tmp, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Every release directory that is neither current nor previous, oldest debris included. */
    fun prunable(): List<Path> {
        val keep = setOfNotNull(pointedVersion(current), pointedVersion(previous))
        if (!Files.isDirectory(releases)) return emptyList()
        return Files.list(releases).use { entries ->
            entries.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) && it.fileName.toString() !in keep }
                .toList()
        }
    }
}
