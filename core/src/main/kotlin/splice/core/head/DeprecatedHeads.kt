// NEW: head-catalog (gw-status). The custom heads the install still has, in the one line `splice status` and
// `splice doctor` say for each: deprecated, and going in a coming release, what to type instead, and how to retire it.
// "A head the install still has" is one of splice.toml's heads whose command link splice laid is still in the install's
// bin folder (the link `WrapperLinks` recognises as splice's own), or whose own Claude folder still holds a
// settings.json splice wrote. Retiring the head (`splice claude retire`) removes both, and the head is not listed.
// Nothing here writes: it reads the topology, the bin folder and the head folders, as both commands do with no daemon.
package splice.core.head

import splice.core.config.CLAUDE_FOLDER_SETTINGS
import splice.core.config.HeadConfigDirs
import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.core.topology.CLAUDE_COMMAND
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink

/** The deprecated custom heads of [topology] on this machine, as [env] reads its bin folder and home. */
public class DeprecatedHeads(
    private val topology: Topology,
    private val env: EnvReader = EnvReader(System::getenv),
) {
    /** One head's line: its [command], the [selector] its pinned model runs as in `claude` (null when it pins none),
     *  and the [text] a person reads. */
    public data class Said(val command: String, val selector: String?, val text: String)

    /** Every head the install still has, one [Said] each, as [onGateway] (whether `claude` is on the Splice gateway)
     *  reads. A head keyed or commanded `claude` is named by the lane line, not here. */
    public fun all(onGateway: Boolean): List<Said> = topology.heads.entries.mapNotNull { (key, head) ->
        val command = head.claude.command?.takeIf { it.isNotBlank() } ?: key
        if (command == CLAUDE_COMMAND || !stillHas(key, head, command)) return@mapNotNull null
        val selector = head.pinnedModel.takeIf { it.isNotBlank() }?.let { head.discoveryPrefix + it }
        Said(command, selector, line(command, selector, onGateway))
    }

    private fun line(command: String, selector: String?, onGateway: Boolean): String {
        val pick = selector?.let { "/model $it" } ?: "/model"
        val run = if (onGateway) "run `claude`" else "run `splice claude door`, then `claude`,"
        return "`$command`: custom head, deprecated and will be removed in a coming release; $run and type `$pick`, " +
            "or retire it with `splice claude retire $command`"
    }

    private fun stillHas(key: String, head: HeadConfig, command: String): Boolean =
        link(command) != null || Files.isRegularFile(folder(key, head).resolve(CLAUDE_FOLDER_SETTINGS))

    /** [command]'s link in the install's bin folder when it is splice's own there: a symlink of that exact name, which
     *  `claude` never is (Claude Code's own link). The rule `WrapperLinks.own` reads for retiring the same link. */
    private fun link(command: String): Path? {
        val bin = InstallPaths(envReader = env).binDir
        val path = bin.resolve(command)
        return path.takeIf {
            command.isNotEmpty() && command != CLAUDE_COMMAND && path.normalize().parent == bin.normalize() &&
                path.isSymbolicLink()
        }
    }

    private fun folder(key: String, head: HeadConfig): Path =
        HeadConfigDirs.of(key, head.claude.configDir, UserHome.dir(env))
}
