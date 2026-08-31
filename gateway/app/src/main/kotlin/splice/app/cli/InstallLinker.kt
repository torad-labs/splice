// NEW: the install/link half of `splice install` — wrapper-symlink creation and
// the whole-topology command-collision check. Split from InstallCommand.kt
// (concentration HIGH, 2026-08-19). Path wrappers stay named methods on
// InstallLayout; they are not inlined into install().
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

internal const val SELF_COMMAND = "splice"

/** The one wrapper name-claiming primitive. A seam (SymlinkOp precedent): the DR-67 safety
 *  property — a foreign file that appears AFTER the precheck wins, never gets eaten — is only
 *  testable on the production path if a test can interleave that creator before the claim. */
internal fun interface WrapperClaim {
    operator fun invoke(link: Path, target: Path)
}

/** The production claim: symlink(2) is exclusive, so it can NEVER replace an existing entry —
 *  the old staged ATOMIC_MOVE + REPLACE_EXISTING replaced whatever sat at the name by move
 *  time, eating a concurrently created foreign file the precheck never saw. */
internal object ExclusiveSymlinkClaim : WrapperClaim {
    override fun invoke(link: Path, target: Path) {
        Files.createSymbolicLink(link, target)
    }
}

internal class InstallLinker(
    private val layout: InstallLayout = InstallLayout(),
    private val heads: InstallHeads = InstallHeads(),
    private val claim: WrapperClaim = ExclusiveSymlinkClaim,
) {

    internal fun install(headArg: String?, env: EnvReader): Boolean {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(env))
        val launchShim = layout.launchShimPath(env)
        check(Files.exists(launchShim)) { "launch shim not found at $launchShim (run install.sh)" }
        val bin = layout.localBin(env)
        Files.createDirectories(bin)
        // All heads for --all/no-arg, else the one named by topology key OR wrapper command (a failed
        // resolution has already printed why — unknown vs ambiguous).
        val selected = if (headArg == null || headArg == "--all") {
            topology.heads
        } else {
            val key = heads.resolveSpecificHead(topology, headArg) ?: return false
            topology.heads.filterKeys { it == key }
        }
        // Validate the WHOLE topology's commands (+ `splice`) on EVERY install, not just this
        // invocation — otherwise sequential single-head installs silently retarget an existing
        // wrapper symlink onto a command another head already owns.
        val commandOwners = topology.heads
            .map { (key, head) -> (head.claude.command ?: key) to key }
            .plus(SELF_COMMAND to SELF_COMMAND)
            .groupBy({ it.first }, { it.second })
        val collisions = commandOwners.filterValues { it.size > 1 }
        check(collisions.isEmpty()) {
            "topology maps multiple heads to one wrapper command: " +
                collisions.entries.joinToString("; ") { (command, keys) -> "$command <- ${keys.joinToString(", ")}" }
        }
        val requested = selected.map { (key, head) -> key to (head.claude.command ?: key) }
        val commands = requested.map { it.second } + SELF_COMMAND
        commands.forEach { command -> requireReplaceableLink(bin.resolve(command)) }
        requested.forEach { (key, command) -> linkOne(bin, key, command, launchShim) }
        linkOne(bin, SELF_COMMAND, SELF_COMMAND, launchShim)
        println("splice: ensure $bin is on your PATH to use the wrappers")
        return true
    }

    /** Link the `splice` admin command itself (so `splice dashboard/status/...` work as commands). */
    internal fun installSelf(env: EnvReader): Boolean {
        val launchShim = layout.launchShimPath(env)
        check(Files.exists(launchShim)) { "launch shim not found at $launchShim (run install.sh)" }
        val bin = layout.localBin(env)
        Files.createDirectories(bin)
        linkOne(bin, SELF_COMMAND, SELF_COMMAND, launchShim)
        return true
    }

    private fun linkOne(bin: Path, headKey: String, command: String, launchShim: Path) {
        val link = bin.resolve(command)
        requireReplaceableLink(link)
        // DR-67: delete only a CONFIRMED symlink, then claim exclusively — a foreign file that
        // appears between the check and the claim wins, and the install fails loud.
        if (link.isSymbolicLink()) Files.deleteIfExists(link)
        try {
            claim(link, launchShim)
            println("splice: installed '$command' -> $launchShim (head=$headKey)")
        } catch (e: java.io.IOException) {
            throw IllegalStateException("failed to link $command — $link was not claimable: ${e.message}", e)
        }
    }

    private fun requireReplaceableLink(link: Path) {
        check(!Files.exists(link, NOFOLLOW_LINKS) || link.isSymbolicLink()) {
            "$link exists and is not a symlink"
        }
    }
}
