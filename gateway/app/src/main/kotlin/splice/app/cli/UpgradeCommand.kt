// NEW: v0.4.0 FEATURES.md §5 — `splice upgrade [--to vX] [--now] [--rollback]` — fetch + verify the
// release exactly as install.sh does, stage it under <share>/releases/<version>/, preflight with the
// candidate's own doctor, wait for every head's inflight to reach zero (or --now), repoint the live
// jar, refresh or keep the wrapper (UpgradeWrapper), restart the unit, run doctor. --rollback
// repoints at the previous release, kept until the next successful upgrade. Config and credentials
// live elsewhere and are never touched; a refusal at any step leaves the previous release active.
// :app: println-exempt.
package splice.app.cli

import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteRecursively

private const val STILL_BUSY = "turns still in flight after the wait — rerun with --now to restart anyway"

internal class UpgradeCommand(
    private val env: EnvReader = EnvReader(System::getenv),
    private val java: String = ProcessHandle.current().info().command().orElse("java"),
    private val release: UpgradeRelease = UpgradeRelease(JdkUpgradeFetch(), JdkUpgradeProcess(), java),
    private val wrapper: UpgradeWrapper = UpgradeWrapper(JdkUpgradeProcess()),
    private val daemon: UpgradeDaemon = UpgradeDaemon(JdkUpgradeProcess(), JdkUpgradeInflight(env)),
    private val layout: UpgradeLayout = UpgradeLayout(env),
) {
    fun upgrade(args: List<String>): Boolean {
        val parsed = UpgradeArgParser().parse(args) ?: return UpgradeArgParser().usage()
        return try {
            if (parsed.rollback) rollback(parsed) else upgradeTo(parsed)
        } catch (refused: UpgradeRefused) {
            val reason = refused.reason
            println("splice upgrade: $reason")
            println("${YELLOW}nothing activated$RESET — ${layout.installedVersion()} stays installed")
            false
        }
    }

    private fun upgradeTo(a: UpgradeArgs): Boolean {
        val base = release.base(a.to, env("SPLICE_RELEASE_BASE_URL"))
        println("${BOLD}splice upgrade$RESET $DIM— from $base$RESET")
        val staging = layout.stagingDir()
        val version = try {
            release.stage(base, staging)
        } finally {
            if (!Files.exists(staging.resolve(JAR_ASSET))) discard(staging)
        }
        val installed = layout.installedVersion()
        if (version == installed) {
            discard(staging)
            println("  ${"version".padEnd(UPGRADE_PAD)} $version is already installed")
            return true
        }
        layout.ensureCurrentRecorded()
        val dir = layout.versionDir(version)
        discard(dir)
        Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE)
        println("  $GREEN✓$RESET ${"staged".padEnd(UPGRADE_PAD)} $version -> $dir")
        if (!daemon.waitIdle(a.now)) {
            println("  $YELLOW!$RESET ${"waiting".padEnd(UPGRADE_PAD)} $STILL_BUSY; the candidate stays staged")
            return false
        }
        activate(version, installed)
        layout.prunable().forEach(::discard)
        return finish()
    }

    private fun rollback(a: UpgradeArgs): Boolean {
        val previous = layout.pointedVersion(layout.previous)
            ?: throw UpgradeRefused("no previous release to roll back to")
        val installed = layout.installedVersion()
        println("${BOLD}splice upgrade --rollback$RESET $DIM— $installed -> $previous$RESET")
        if (!daemon.waitIdle(a.now)) {
            println("  $YELLOW!$RESET ${"waiting".padEnd(UPGRADE_PAD)} $STILL_BUSY; nothing changed")
            return false
        }
        activate(previous, installed)
        return finish()
    }

    /** Repoint the live jar, refresh or keep the wrapper, then move the current/previous links. */
    private fun activate(version: String, from: String) {
        val dir = layout.versionDir(version)
        layout.point(layout.liveJar, layout.share.relativize(dir.resolve(JAR_ASSET)))
        wrapper.activate(layout.liveShim, layout.versionDir(from).resolve(SHIM_ASSET), dir.resolve(SHIM_ASSET))
        layout.point(layout.previous, Path.of(from))
        layout.point(layout.current, Path.of(version))
        println("  $GREEN✓$RESET ${"activated".padEnd(UPGRADE_PAD)} $version ($from kept for --rollback)")
    }

    private fun finish(): Boolean {
        val restarted = daemon.restart(layout.liveJar)
        val glyph = if (restarted) "$GREEN✓$RESET" else "$RED✗$RESET"
        val outcome = if (restarted) "restarted" else "did not restart — run: splice restart"
        println("  $glyph ${"daemon".padEnd(UPGRADE_PAD)} $outcome")
        println()
        daemon.doctor(java, layout.liveJar)
        return restarted
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun discard(path: Path) {
        if (Files.exists(path)) path.deleteRecursively()
    }
}
