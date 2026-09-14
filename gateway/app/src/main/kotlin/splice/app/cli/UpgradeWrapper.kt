// NEW: v0.4.0 FEATURES.md §5 — the launch shim is the one artifact an operator may have edited
// after install (the hostshield launcher patch rewrites it in place). An upgrade compares the live
// shim with the PRISTINE copy of the release it came from: identical -> replaced by the new
// release's shim; different, or no pristine copy to compare with -> KEPT, and the diff against the
// new release's copy is printed so the operator can port the edit. Never overwritten.
package splice.app.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class UpgradeWrapper(private val process: UpgradeProcess) {

    /** True when [live] now carries [release]; false when it was kept. */
    fun activate(live: Path, pristine: Path?, release: Path): Boolean {
        val present = Files.exists(live)
        val untouched = pristine != null && Files.exists(pristine) && present &&
            Files.readAllBytes(live).contentEquals(Files.readAllBytes(pristine))
        if (!present || untouched) {
            replace(live, release)
            println("  $GREEN✓$RESET ${"wrapper".padEnd(UPGRADE_PAD)} $live refreshed from the release")
            return true
        }
        val why = if (pristine == null || !Files.exists(pristine)) {
            "no pristine copy of the installed release to compare with"
        } else {
            "edited since its release was installed"
        }
        println("  $YELLOW!$RESET ${"wrapper".padEnd(UPGRADE_PAD)} $live kept ($why)")
        println("  ${"".padEnd(UPGRADE_PAD)}   diff against the new release's copy:")
        println(diff(release, live))
        return false
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
