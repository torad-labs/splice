// NEW: v0.4.0 FEATURES.md §5 — the activation step of `splice upgrade` as one transaction: the wrapper
// first (its I/O can fail before anything is exposed), then the previous and current links, the live
// jar last — and every pointer restored when any step throws, so a failure never leaves the daemon on
// a release the metadata does not name. When the restoration itself fails, the refusal SAYS which
// pointer it could not put back, never a bare exception. Split from UpgradeCommand.kt (2026-09-13).
package splice.app.cli.upgrade

import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Repoints one symlink atomically — UpgradeLayout.point in production, a failing stand-in in tests. */
internal fun interface LinkPointer {
    operator fun invoke(link: Path, target: Path)
}

internal class UpgradeActivation(
    private val layout: UpgradeLayout,
    private val wrapper: UpgradeWrapper,
    private val point: LinkPointer = LinkPointer(layout::point),
) {

    /** One transaction: the wrapper first (its I/O can fail; nothing is exposed yet), then the
     *  previous/current links, the live jar LAST — and every pointer restored if any step throws,
     *  so a failure never leaves the daemon on a release the metadata does not name. */
    fun activate(version: String, from: String) {
        val dir = layout.versionDir(version)
        val fromDir = layout.versionDir(from)
        val links = mapOf(
            layout.previous to layout.pointedVersion(layout.previous)?.let(Path::of),
            layout.current to layout.pointedVersion(layout.current)?.let(Path::of),
        )
        val liveBefore = if (Files.isSymbolicLink(layout.liveJar)) Files.readSymbolicLink(layout.liveJar) else null
        val hadShim = Files.exists(layout.liveShim)
        var previousShim: Path? = null
        Cancellables.runCatchingBestEffort {
            previousShim = wrapper.activate(
                layout.liveShim,
                fromDir.resolve(SHIM_ASSET),
                dir.resolve(SHIM_ASSET),
                fromDir.resolve(EDITED_SHIM),
            )
            point(layout.previous, Path.of(from))
            point(layout.current, Path.of(version))
            point(layout.liveJar, layout.share.relativize(dir.resolve(JAR_ASSET)))
        }.getOrElse { e ->
            val failed = restore(links, liveBefore, previousShim, hadShim)
            val why = "activating $version failed (${SafeFailureText.render(e)})"
            throw UpgradeRefused(if (failed.isEmpty()) "$why; $from restored" else "$why; recovery FAILED for $failed")
        }
        println("  $GREEN✓$RESET ${"activated".padEnd(UPGRADE_PAD)} $version ($from kept for --rollback)")
    }

    /** Puts every pointer back, continuing past a step that fails; returns the names it could NOT
     *  restore, so the operator is told to run doctor instead of trusting the metadata. */
    private fun restore(links: Map<Path, Path?>, liveBefore: Path?, shim: Path?, hadShim: Boolean): List<String> {
        val failed = mutableListOf<String>()
        links.forEach { (link, target) ->
            attempt(failed, link.fileName.toString()) {
                if (target != null) point(link, target) else Files.deleteIfExists(link)
            }
        }
        if (liveBefore != null) {
            attempt(failed, layout.liveJar.fileName.toString()) { point(layout.liveJar, liveBefore) }
        }
        if (shim != null && Files.exists(shim)) {
            attempt(failed, layout.liveShim.fileName.toString()) {
                Files.copy(shim, layout.liveShim, StandardCopyOption.REPLACE_EXISTING)
            }
        } else if (!hadShim) {
            // No shim before this activation: the one the wrapper wrote is the new release's, and a
            // half-activated install must not keep it (review 2026-09-14).
            attempt(failed, layout.liveShim.fileName.toString()) { Files.deleteIfExists(layout.liveShim) }
        }
        return failed
    }

    private fun attempt(failed: MutableList<String>, name: String, step: RestoreStep) {
        Cancellables.runCatchingBestEffort { step.run() }
            .onFailure { failed += "$name (${SafeFailureText.render(it)})" }
    }
}

/** One step of putting the previous release back: a pointer flip or a shim copy. */
internal fun interface RestoreStep {
    fun run()
}
