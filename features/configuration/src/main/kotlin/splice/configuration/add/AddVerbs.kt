// NEW: LAYOUT-01 — the two verbs app composes, `splice add` over AddCommand and `splice add-model`
// over AddModelVerb. Each keeps its test seams (the checks' network, the roster editor) internal; app
// supplies only the terminal and the ports in AddSeams.
package splice.configuration.add

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.terminal.MultiSelectPrompt
import splice.terminal.SelectPrompt
import java.nio.file.Path

/** `splice add <profile>`. [output] takes every line the verb prints; [errors] only what resolving the
 *  control port says about a splice.toml that does not load. */
public class AddVerb(output: TerminalOutput, errors: TerminalOutput, ports: AddPorts) {
    private val command = AddCommand(output, errors, AddChecks(output), ports)

    /** True when the head was saved and is reachable as printed; false leaves splice.toml unchanged
     *  or names the restart that is still owed. */
    public suspend fun add(args: List<String>, env: EnvReader): Boolean = command.add(args, env)
}

/** `splice add-model`: OpenRouter catalog rows picked through [select] and [multi]. */
public class AddModelsVerb(select: SelectPrompt, multi: MultiSelectPrompt) {
    private val verb = AddModelVerb(select, multi)

    /** True when the picked rows were written to the splice.toml at [path]; a refused edit throws
     *  [AddRefused] with the whole explanation. */
    public fun add(path: Path): Boolean = verb.add(path)
}
