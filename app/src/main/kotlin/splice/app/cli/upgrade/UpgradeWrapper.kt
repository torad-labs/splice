// NEW: v0.4.0 FEATURES.md §5 — the launch shim is the one artifact an operator may have edited
// after install (a host that patches the launcher rewrites it in place), and it is version-locked to
// the jar: bin/splice-launch shuts a daemon down and refuses to launch when the daemon's version is
// not its own SPLICE_GATEWAY_VERSION. So an upgrade ALWAYS activates the new release's shim; a kept
// old shim cannot launch anything (review 2026-09-14: every flat 0.3.x install kept its shim and lost
// every launch). A live shim that differs from its release's pristine copy — or has no pristine copy
// to compare with — is SAVED beside the release it belonged to (splice-launch.edited) and the diff
// against the new release's copy is printed so the operator can port the edit. Never lost.
package splice.app.cli.upgrade

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class UpgradeWrapper(private val process: UpgradeProcess) {

    /** Activates [release] at [live]. Returns the file holding the bytes [live] had before — [pristine]
     *  when they were identical, [keepEditAt] (written here) when they differed or no pristine copy
     *  exists — so a failed activation can put them back; null when there was no live shim. */
    fun activate(live: Path, pristine: Path?, release: Path, keepEditAt: Path): Path? {
        val previous = keep(live, pristine, keepEditAt)
        replace(live, release)
        report(live, release, pristine, previous)
        return previous
    }

    private fun keep(live: Path, pristine: Path?, keepEditAt: Path): Path? {
        if (!Files.exists(live)) return null
        val untouched = pristine != null && Files.exists(pristine) &&
            Files.readAllBytes(live).contentEquals(Files.readAllBytes(pristine))
        if (untouched) return pristine
        Files.copy(live, keepEditAt, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        return keepEditAt
    }

    private fun report(live: Path, release: Path, pristine: Path?, previous: Path?) {
        val label = "wrapper".padEnd(UPGRADE_PAD)
        if (previous == null || previous == pristine) {
            println("  $GREEN✓$RESET $label $live refreshed from the release")
            return
        }
        val comparable = pristine != null && Files.exists(pristine)
        val why = if (comparable) "edited since its release was installed" else "no pristine copy to compare with"
        println("  $YELLOW!$RESET $label $live refreshed from the release ($why)")
        val indent = "".padEnd(UPGRADE_PAD)
        println("  $indent   your copy is saved at $previous; its diff against the new release's copy:")
        println(diff(release, previous))
    }

    private fun replace(live: Path, release: Path) {
        val stamp = "${ProcessHandle.current().pid()}-${System.nanoTime()}"
        val tmp = live.resolveSibling(".${live.fileName}.upgrade-$stamp.tmp")
        Files.copy(release, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        Files.move(tmp, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun diff(release: Path, live: Path): String {
        val out = process(listOf("diff", "-u", release.toString(), live.toString()), false).stdout
        val counts = "release ${Files.readAllLines(release).size} lines, live ${Files.readAllLines(live).size} lines"
        val text = out.ifBlank { "(diff unavailable: $counts)" }
        return text.trimEnd().prependIndent("    ")
    }
}
