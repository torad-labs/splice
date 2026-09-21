// NEW: v0.4.0 FEATURES.md §5 — `splice upgrade [--to vX] [--now] [--rollback]` — fetch + verify the
// release exactly as install.sh does, stage it under <share>/releases/<version>/, preflight with the
// candidate's own doctor, wait for every head's inflight to reach zero (or --now), repoint the live
// jar, refresh or keep the wrapper (UpgradeWrapper), restart the unit, run doctor. --rollback
// repoints at the previous release, kept until the next successful upgrade. Config and credentials
// live elsewhere and are never touched; a refusal at any step leaves the previous release active.
// :app: println-exempt.
package splice.app.cli.upgrade

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val STILL_BUSY = "turns still in flight after the wait — rerun with --now to restart anyway"

internal class UpgradeCommand(
    private val env: EnvReader = EnvReader(System::getenv),
    private val java: String = ProcessHandle.current().info().command().orElse("java"),
    private val release: UpgradeRelease = UpgradeRelease(JdkUpgradeFetch(), JdkUpgradeProcess(), java),
    private val wrapper: UpgradeWrapper = UpgradeWrapper(JdkUpgradeProcess()),
    private val daemon: UpgradeDaemon = UpgradeDaemon(JdkUpgradeProcess(), JdkUpgradeInflight(env)),
    private val layout: UpgradeLayout = UpgradeLayout(env),
) {
    private val activation = UpgradeActivation(layout, wrapper)
    private val lock = UpgradeLock(layout)
    fun upgrade(args: List<String>): Boolean {
        val parsed = UpgradeArgParser().parse(args) ?: return UpgradeArgParser().usage()
        return try {
            if (parsed.rollback && parsed.to != null) {
                throw UpgradeRefused("--rollback takes no --to: it repoints at the previous release only")
            }
            lock.held { if (parsed.rollback) rollback(parsed) else upgradeTo(parsed) }
        } catch (refused: UpgradeRefused) {
            val reason = refused.reason
            println("splice upgrade: $reason")
            println("${YELLOW}nothing activated$RESET — ${layout.installedVersion()} stays installed")
            false
        }
    }

    private fun upgradeTo(a: UpgradeArgs): Boolean {
        a.to?.let { layout.versionDir(it.removePrefix("v")) }
        val base = release.base(a.to, env("SPLICE_RELEASE_BASE_URL"))
        println("${BOLD}splice upgrade$RESET $DIM— from $base$RESET")
        val staging = layout.stagingDir()
        val version = staged(base, staging, a.to)
        val installed = layout.installedVersion()
        if (version == installed) {
            layout.discard(staging)
            println("  ${"version".padEnd(UPGRADE_PAD)} $version is already installed")
            return true
        }
        layout.ensureCurrentRecorded()
        val dir = layout.versionDir(version)
        layout.discard(dir)
        if (!Files.isDirectory(staging)) {
            throw UpgradeRefused("the staged release at $staging disappeared (another upgrade running?); rerun")
        }
        Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE)
        println("  $GREEN✓$RESET ${"staged".padEnd(UPGRADE_PAD)} $version -> $dir")
        if (!daemon.waitIdle(a.now)) {
            println("  $YELLOW!$RESET ${"waiting".padEnd(UPGRADE_PAD)} $STILL_BUSY; the candidate stays staged")
            return false
        }
        activation.activate(version, installed)
        layout.prunable().forEach(layout::discard)
        return finish(version)
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
        activation.activate(previous, installed)
        return finish(previous)
    }

    /** Fetch, verify and validate into [staging]; ANY failure after the first byte removes the staging
     *  directory, and a `--to` that the candidate jar does not confirm is a refusal, not a rename. */
    private fun staged(base: String, staging: Path, requested: String?): String {
        // runCatchingCancellable folds I/O and parse failures into a refusal; a refusal thrown by the
        // verifier itself passes straight through it, so the cleanup catches the refusal, not the Result.
        return try {
            val version = Cancellables.runCatchingCancellable { release.stage(base, staging) }
                .getOrElse { e -> throw UpgradeRefused("staging failed: ${SafeFailureText.render(e)}") }
            layout.confirmVersion(version, requested)
        } catch (refused: UpgradeRefused) {
            layout.discard(staging)
            throw refused
        }
    }

    private fun finish(version: String): Boolean {
        val restart = daemon.restart(layout.liveJar, version)
        val restarted = restart is DaemonRestarted.Serving
        val glyph = if (restarted) "$GREEN✓$RESET" else "$RED✗$RESET"
        val outcome = when (restart) {
            DaemonRestarted.Serving -> "restarted, serving $version"
            is DaemonRestarted.StillOld ->
                "still serves ${restart.version} (a daemon started by hand?) — run: splice restart"
            DaemonRestarted.NotAnswering -> "did not answer after the restart — run: splice restart"
        }
        println("  $glyph ${"daemon".padEnd(UPGRADE_PAD)} $outcome")
        println()
        daemon.doctor(java, layout.liveJar)
        return restarted
    }
}
