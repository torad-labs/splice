// NEW: v0.4.0 FEATURES.md §5 — where `splice upgrade` keeps releases and what it repoints. The
// layout is the one install.sh already writes (jar + shim under the share dir, wrappers in bin,
// config and credentials elsewhere — never touched here) plus one directory per release under
// <share>/releases/<version>/ holding the PRISTINE jar and launch shim, with `current` and
// `previous` links. The live jar becomes a symlink into the current release; the live shim stays a
// real file so a local edit survives — a host that patches the shim after install is the case this
// is for, and UpgradeWrapper decides what happens to such an edit.
package splice.app.cli.upgrade

import splice.app.cli.install.InstallLayout
import splice.core.GATEWAY_VERSION
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteRecursively

internal const val JAR_ASSET = "splice.jar"
internal const val SHIM_ASSET = "splice-launch"

/** Where an upgrade saves a live shim that differed from its release's copy (UpgradeWrapper). */
internal const val EDITED_SHIM = "splice-launch.edited"
private const val STAGING_PREFIX = ".staging-"
internal const val UPGRADE_PAD = 11
private const val CURRENT_LINK = "current"
private const val PREVIOUS_LINK = "previous"

// SemVer 2.0.0 (semver.org, the canonical grammar): no leading zeros in the numeric parts or numeric
// prerelease identifiers, no empty identifiers, hyphens allowed inside identifiers, build metadata after +.
private const val NUM = """(?:0|[1-9]\d*)"""
private const val PRE_ID = """(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)"""
private const val SEMVER_SEGMENT =
    """$NUM\.$NUM\.$NUM(?:-$PRE_ID(?:\.$PRE_ID)*)?(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?"""

internal class UpgradeLayout(env: EnvReader, installLayout: InstallLayout = InstallLayout()) {
    private val semver = Regex(SEMVER_SEGMENT)
    val share: Path = installLayout.shareDir(env)
    val liveJar: Path = share.resolve(JAR_ASSET)
    val liveShim: Path = installLayout.launchShimPath(env)
    val releases: Path = share.resolve("releases")
    val current: Path = releases.resolve(CURRENT_LINK)
    val previous: Path = releases.resolve(PREVIOUS_LINK)

    /** Releases live exactly one normalized SemVer 2.0.0 segment below releases/: a link name ("current"),
     *  "..", an absolute path, a leading zero or an empty identifier is refused BEFORE a path is built,
     *  whoever supplies it — a candidate jar's version line, --to, or a previous link's target. */
    fun versionDir(version: String): Path {
        if (!semver.matches(version)) {
            throw UpgradeRefused("release version '$version' is not a normalized SemVer version")
        }
        return releases.resolve(version)
    }

    fun stagingDir(): Path = releases.resolve("$STAGING_PREFIX${ProcessHandle.current().pid()}")

    /** The version the live jar points at: the current link when one exists, else this CLI's own. */
    fun installedVersion(): String = pointedVersion(current) ?: GATEWAY_VERSION

    fun pointedVersion(link: Path): String? =
        if (Files.isSymbolicLink(link)) Files.readSymbolicLink(link).fileName.toString() else null

    /** A flat install (install.sh before 0.4.0) has no release copy of what it runs; record the live
     *  jar AND shim under its version so rollback has somewhere to go. The live shim is the one that
     *  pairs with that jar (a shim and a jar are version-locked by the launch handshake), local edit
     *  included: the pristine bytes are gone, and a rollback that copied nothing failed on the
     *  missing file (review 2026-09-14). */
    fun ensureCurrentRecorded() {
        if (Files.isSymbolicLink(current)) return
        val dir = versionDir(installedVersion())
        Files.createDirectories(dir)
        if (!Files.exists(dir.resolve(JAR_ASSET))) {
            Files.copy(liveJar, dir.resolve(JAR_ASSET), StandardCopyOption.COPY_ATTRIBUTES)
        }
        if (!Files.exists(dir.resolve(SHIM_ASSET)) && Files.exists(liveShim)) {
            Files.copy(liveShim, dir.resolve(SHIM_ASSET), StandardCopyOption.COPY_ATTRIBUTES)
        }
        point(current, dir.fileName)
    }

    /** Symlink swap: a sibling temp link, then ONE rename — the name never dangles, and a regular
     *  file at [link] (the flat install's jar) is replaced the same way. */
    fun point(link: Path, target: Path) {
        // Named per process and instant: two upgrades racing on one install never share a temp link.
        val stamp = "${ProcessHandle.current().pid()}-${System.nanoTime()}"
        val tmp = link.resolveSibling("${link.fileName}.upgrade-$stamp.tmp")
        Files.createSymbolicLink(tmp, target)
        Files.move(tmp, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Every release directory that is neither current nor previous, oldest debris included — but
     *  never the staging directory of an upgrade that is still running (its pid is alive). */
    fun prunable(): List<Path> {
        val keep = setOfNotNull(pointedVersion(current), pointedVersion(previous))
        if (!Files.isDirectory(releases)) return emptyList()
        return Files.list(releases).use { entries ->
            entries.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) && it.fileName.toString() !in keep }
                .filter { !liveStaging(it.fileName.toString()) }
                .toList()
        }
    }

    private fun liveStaging(name: String): Boolean {
        val pid = name.removePrefix(STAGING_PREFIX).takeIf { name.startsWith(STAGING_PREFIX) }?.toLongOrNull()
        return pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun discard(path: Path) {
        if (Files.exists(path)) path.deleteRecursively()
    }

    /** The jar's own version line must be a plain semver segment (versionDir refuses anything else)
     *  and must confirm --to when one was given. */
    fun confirmVersion(version: String, requested: String?): String {
        versionDir(version)
        val wanted = requested?.removePrefix("v")
        if (wanted != null && wanted != version) {
            throw UpgradeRefused("release $requested delivered a jar reporting $version — refusing to activate it")
        }
        return version
    }
}
