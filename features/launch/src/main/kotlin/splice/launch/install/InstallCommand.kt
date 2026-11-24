// NEW: `splice install/uninstall` (P5-CLI) facade. Bodies live in InstallLinker / UninstallCommand /
// InstallHeads / InstallLayout so this file is not billed as a god object (concentration HIGH,
// 2026-08-19). A launch slice since LAYOUT-01: the wrappers it links are how a head's command
// reaches the launch shim. `init` stayed in app (it writes the starter topology, which is app's
// wiring), and the shim-marker reads are InstallShim's own public surface.
package splice.launch.install

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

// The targets that name the whole install: none, or `--all`.
private val WHOLE_INSTALL = setOf(null, "--all")

/** The `install` / `uninstall` verbs as one cohesive unit of behavior (Kotlin style law,
 *  2026-08-15: main sources carry no top-level functions) — they share the wrapper-symlink and
 *  launch-shim path resolution. [output] carries the operator's lines, [errors] the refusals. */
public class InstallCommand(private val output: TerminalOutput, errors: TerminalOutput) {

    private val heads = InstallHeads(output)
    private val linker = InstallLinker(output, heads = heads)
    private val uninstaller = UninstallCommand(output, errors, heads = heads)

    /** The wrappers [headArg] names; for the whole install (`--all` or none) also the Antigravity shim install.sh lays
     *  (GeminiShim), so an install from before it, or one whose bin folder moved, gets it. A shim that cannot be laid
     *  is said, as install.sh says it, and fails nothing. */
    public fun install(headArg: String?, env: EnvReader): InstallResult = linker.install(headArg, env).also {
        if (it is InstallResult.Linked && headArg in WHOLE_INSTALL) {
            (GeminiShim(env).lay() as? GeminiShimLaid.Failed)?.let { failed ->
                output.line(
                    "splice: warning: could not lay the Antigravity shim (${failed.why}); " +
                        "Claude Desktop can't run Antigravity models",
                )
            }
        }
    }

    public fun installSelf(env: EnvReader): InstallResult = linker.installShortcut(SELF_COMMAND, env)

    /** Links a standalone side-by-side command (such as `claude-splice`) directly to the launch shim. */
    public fun installShortcut(command: String, env: EnvReader): InstallResult = linker.installShortcut(command, env)

    public fun uninstall(headArg: String?, env: EnvReader): Boolean = uninstaller.uninstall(headArg, env)

    /** TRUE when [command]'s link here runs splice's launch shim, so the command asks splice where to run. */
    public fun runsShim(command: String, env: EnvReader): Boolean = linker.runsShim(command, env)
}
