// NEW: `splice install/uninstall` (P5-CLI) facade. Bodies live in InstallLinker / UninstallCommand /
// InstallHeads / InstallLayout so this file is not billed as a god object (concentration HIGH,
// 2026-08-19). A launch slice since LAYOUT-01: the wrappers it links are how a head's command
// reaches the launch shim. `init` stayed in app (it writes the starter topology, which is app's
// wiring), and the shim-marker reads are InstallShim's own public surface.
package splice.launch.install

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

/** The `install` / `uninstall` verbs as one cohesive unit of behavior (Kotlin style law,
 *  2026-08-15: main sources carry no top-level functions) — they share the wrapper-symlink and
 *  launch-shim path resolution. [output] carries the operator's lines, [errors] the refusals. */
public class InstallCommand(output: TerminalOutput, errors: TerminalOutput) {

    private val heads = InstallHeads(output)
    private val linker = InstallLinker(output, heads = heads)
    private val uninstaller = UninstallCommand(output, errors, heads = heads)

    public fun install(headArg: String?, env: EnvReader): Boolean = linker.install(headArg, env)

    public fun installSelf(env: EnvReader): Boolean = linker.installSelf(env)

    public fun uninstall(headArg: String?, env: EnvReader): Boolean = uninstaller.uninstall(headArg, env)
}
