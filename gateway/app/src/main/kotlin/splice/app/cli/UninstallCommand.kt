// NEW: `splice uninstall` — wrapper-symlink removal and the target-resolution
// rule (every head + `splice` for --all, else the one named head). Split from
// InstallCommand.kt (concentration HIGH, 2026-08-19).
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import kotlin.io.path.isSymbolicLink

internal class UninstallCommand(
    private val layout: InstallLayout = InstallLayout(),
    private val heads: InstallHeads = InstallHeads(),
) {

    internal fun uninstall(headArg: String?, env: EnvReader): Boolean {
        val commands = uninstallTargets(headArg, env) ?: return false
        val bin = layout.localBin(env)
        var ok = true
        for (command in commands) {
            val link = bin.resolve(command)
            Cancellables.runCatchingCancellable {
                if (link.isSymbolicLink()) {
                    Files.delete(link)
                    println("splice: removed '$command'")
                }
            }.onFailure { e ->
                ok = false
                println("splice: failed to remove $command: ${e.message}")
            }
        }
        return ok
    }

    // Which wrapper commands a `splice uninstall [head]` removes: every head command (+ `splice`
    // itself) for --all, else the one head named by topology key OR wrapper command. Returns null (and
    // prints) for an unknown or ambiguous specific arg, so uninstall fails loudly instead of exiting 0
    // with no output; a null/unreadable topology falls back to removing by the literal name typed.
    private fun uninstallTargets(headArg: String?, env: EnvReader): List<String>? {
        val topology = runCatching {
            TopologyLoader.parse(Files.readString(TopologyLoader.configPath(env)))
        }.getOrNull()
        if (headArg == null || headArg == "--all") {
            val headCommands = topology?.heads?.map { (k, h) -> h.claude.command ?: k } ?: listOfNotNull(headArg)
            return (headCommands + SELF_COMMAND).distinct()
        }
        if (topology == null) return listOf(headArg)
        return heads.resolveSpecificHead(topology, headArg)?.let { key ->
            listOf(topology.heads.getValue(key).claude.command ?: key)
        }
    }
}
