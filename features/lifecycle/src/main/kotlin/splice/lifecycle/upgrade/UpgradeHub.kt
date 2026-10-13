// NEW: the MCP hub's two programs ride `splice upgrade` the way install.sh gets them (splice-lead, Oct 13). A release
// whose sha256sums.txt lists splice-mcp-holder and splice-mcp-front has both staged in its release folder on Linux
// x86-64 (UpgradeRelease), and activation swaps them into the share folder in the same transaction as the jar. The
// live pair is first kept in the release being left, so a failed activation and a later --rollback both put it back.
// A release folder holding only one of the two swaps neither, as install.sh installs a pair or none, and one holding
// neither leaves the installed pair where it is. The hub's systemd units are never touched, whoever placed them
// (hostshield or `splice install`): only the programs they run are replaced, by rename, so a running front or holder
// keeps its own file. A running front then takes the new program by reload (USR2), never a restart, which would drop
// every session's MCP connection (install.sh step 3b, hostshield-lead's contract).
package splice.lifecycle.upgrade

import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.terminal.YELLOW
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal const val HUB_HOLDER = "splice-mcp-holder"
internal const val HUB_FRONT = "splice-mcp-front"
private const val FRONT_UNIT = "splice-mcp-front.service"

internal class UpgradeHub(
    private val output: TerminalOutput,
    private val share: Path,
    private val process: UpgradeProcess,
) {
    private val names = listOf(HUB_HOLDER, HUB_FRONT)

    /** The pair [dir] holds: both programs, or none when it holds one or neither. */
    fun pair(dir: Path): List<String> =
        names.takeIf { all -> all.all { Files.isRegularFile(dir.resolve(it)) } }.orEmpty()

    /** Swaps [dir]'s pair in over the live one, after keeping the live one in [fromDir]. Each program lands in
     *  [swapped] as its rename completes, with whether a live one was there, so [restore] undoes exactly that. */
    fun swap(dir: Path, fromDir: Path, swapped: MutableMap<String, Boolean>) {
        val pair = pair(dir)
        val hadLive = pair.associateWith { Files.isRegularFile(share.resolve(it)) }
        pair.forEach { keep(it, fromDir) }
        pair.forEach {
            replace(dir.resolve(it), share.resolve(it))
            swapped[it] = hadLive.getValue(it)
        }
    }

    /** Puts back what [swap] replaced for [name]: the copy kept in [fromDir], or no program when none was live. */
    fun restore(name: String, hadLive: Boolean, fromDir: Path) {
        if (hadLive) replace(fromDir.resolve(name), share.resolve(name)) else Files.deleteIfExists(share.resolve(name))
    }

    /** Says the pair went in, and has a running front take it by reload; a front that is not running starts on the
     *  new program next time. */
    fun swapped() {
        output.line("  $GREEN✓$RESET ${"mcp hub".padEnd(UPGRADE_PAD)} $HUB_HOLDER and $HUB_FRONT swapped in")
        val active = process(listOf("systemctl", "--user", "is-active", "--quiet", FRONT_UNIT), false).code == 0
        if (!active) return
        if (process(listOf("systemctl", "--user", "reload", FRONT_UNIT), false).code == 0) {
            output.line("  $GREEN✓$RESET ${"mcp front".padEnd(UPGRADE_PAD)} reloaded onto the new program")
        } else {
            output.line(
                "  $YELLOW!$RESET ${"mcp front".padEnd(UPGRADE_PAD)} $FRONT_UNIT did not reload; " +
                    "it keeps its old program until it next starts",
            )
        }
    }

    /** The live program's bytes, kept in the release being left unless it already holds the same bytes. */
    private fun keep(name: String, fromDir: Path) {
        val live = share.resolve(name)
        if (!Files.isRegularFile(live)) return
        val kept = fromDir.resolve(name)
        if (Files.isRegularFile(kept) && Files.mismatch(live, kept) == -1L) return
        Files.createDirectories(fromDir)
        replace(live, kept)
    }

    /** A sibling temp copy, then one rename: [to] is never half written, and a process running the old file
     *  keeps it. */
    private fun replace(from: Path, to: Path) {
        val tmp = to.resolveSibling(".${to.fileName}.upgrade-${ProcessHandle.current().pid()}-${System.nanoTime()}.tmp")
        try {
            Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            Files.move(tmp, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
