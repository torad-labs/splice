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
import java.nio.file.StandardCopyOption
import kotlin.io.path.isSymbolicLink

internal const val SELF_COMMAND = "splice"

internal class InstallLinker(
    private val layout: InstallLayout = InstallLayout(),
    private val heads: InstallHeads = InstallHeads(),
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
        val candidate = Files.createTempFile(bin, ".$command.", ".link")
        try {
            Files.delete(candidate)
            Files.createSymbolicLink(candidate, launchShim)
            Files.move(
                candidate,
                link,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            println("splice: installed '$command' -> $launchShim (head=$headKey)")
        } catch (e: java.io.IOException) {
            Files.deleteIfExists(candidate)
            throw IllegalStateException("failed to link $command: ${e.message}", e)
        }
    }

    private fun requireReplaceableLink(link: Path) {
        check(!Files.exists(link, NOFOLLOW_LINKS) || link.isSymbolicLink()) {
            "$link exists and is not a symlink"
        }
    }
}
