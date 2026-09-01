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
                // SAFE-RENDER-EXEMPT[2026-08-31]: Files.delete of a bin symlink — the failure names the link path, never file content
                println("splice: failed to remove $command: ${e.message}")
            }
        }
        return ok
    }

    // Which wrapper commands a `splice uninstall [head]` removes: every head command (+ `splice`
    // itself) for --all, else the one head named by topology key OR wrapper command. Returns null (and
    // prints) for an unknown or ambiguous specific arg, so uninstall fails loudly instead of exiting 0
    // with no output; a specific arg against an unreadable topology falls back to the literal name
    // typed (the operator named the link; the topology only disambiguates).
    private fun uninstallTargets(headArg: String?, env: EnvReader): List<String>? {
        val configPath = TopologyLoader.configPath(env)
        val attempt = Cancellables.runCatchingCancellable {
            TopologyLoader.parse(Files.readString(configPath))
        }
        val topology = attempt.getOrNull()
        if (headArg == null || headArg == "--all") return allTargets(attempt, configPath)
        if (topology == null) return listOf(headArg)
        return heads.resolveSpecificHead(topology, headArg)?.let { key ->
            listOf(topology.heads.getValue(key).claude.command ?: key)
        }
    }

    /** DR-101: --all with no readable topology must never silently shrink to the self-link (the
     *  old fallback was the literal "--all", so head wrappers stayed installed while the command
     *  exited 0 saying nothing). Genuine absence (NoSuchFileException — the positive evidence of
     *  absence) is a fresh box: self-link only, but SAID. Anything else is an unreadable/corrupt
     *  config: refuse loudly with nothing removed, so the retry after the fix removes everything. */
    private fun allTargets(
        attempt: Result<splice.core.topology.Topology>,
        configPath: java.nio.file.Path,
    ): List<String>? {
        val topology = attempt.getOrNull()
        if (topology != null) {
            return (topology.heads.map { (k, h) -> h.claude.command ?: k } + SELF_COMMAND).distinct()
        }
        if (attempt.exceptionOrNull() is java.nio.file.NoSuchFileException) {
            println("splice: no config at $configPath — removing only the '$SELF_COMMAND' link")
            return listOf(SELF_COMMAND)
        }
        val reason = attempt.exceptionOrNull()?.let { splice.core.util.SafeFailureText.render(it) } ?: "unknown"
        System.err.println(
            "splice uninstall --all: $configPath unreadable ($reason) — " +
                "head wrappers NOT removed; fix or delete the config and re-run",
        )
        return null
    }
}
