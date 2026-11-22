// NEW: head-catalog (gw-check), split from ClaudeStarts so each file holds one job: how `splice claude check`
// gathers what it reports. `claude` and the heads come from the daemon, which says how each command launches now
// without launching it (HeadRetirement.launchFacts); a head's command counts while splice's link for it is in the
// install's bin folder. The launchers on PATH and the model lists are read from the files: a head's folder (splice's,
// which retiring removes), the person's own ~/.claude settings, every CLAUDE_CONFIG_DIR a launcher names, and the
// managed settings, which are reported and never edited.
package splice.app.cli.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.app.desktop.lane.DOOR_LANE
import splice.app.head.WrapperBinSource
import splice.app.head.WrapperLinks
import splice.client.ClaudeModelLists
import splice.core.config.CLAUDE_FOLDER_SETTINGS
import splice.core.config.UserHome
import splice.core.topology.CLAUDE_COMMAND
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Path
import java.nio.file.Paths

/** How the checks are gathered from the daemon's [facts] and this machine's files. */
internal class ClaudeStartsRead(
    private val launchers: ClaudeLaunchers,
    bin: Path,
    private val home: Path,
    private val os: String,
) {
    private val links = WrapperLinks(WrapperBinSource { bin }, LogSink {})

    fun read(facts: JsonObject): ClaudeStarts {
        val heads = (facts["heads"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val found = launchers.all()
        val headLists = heads.mapNotNull { JsonScalars.str(it, "folder") }.flatMap { folder ->
            listOf(Paths.get(folder).resolve(CLAUDE_FOLDER_SETTINGS), Paths.get(folder).resolve(STATE_FILE))
                .mapNotNull { lists(it, true) }
        }
        val launcherLists = found.mapNotNull { it.configDir }.flatMap(::folderLists)
        val own = folderLists(home.resolve(UserHome.CLAUDE_DIR))
        val managed = listOfNotNull(ClaudeModelLists.managed(os)?.let { lists(it, false) })
        val lists = (headLists + own + launcherLists + managed).distinctBy { it.path.toAbsolutePath().normalize() }
        val listed = lists.map { it.path.parent.toAbsolutePath().normalize() }.toSet()
        return ClaudeStarts(
            listOf(claude(JsonScalars.str(facts, "lane"))) + heads.mapNotNull(::head) +
                found.map { launcher(it, it.configDir?.toAbsolutePath()?.normalize() in listed) },
            lists,
        )
    }

    private fun claude(lane: String?): ClaudeStart {
        val on = lane == DOOR_LANE
        val why = if (on) null else "on its head, on your own Claude login"
        return ClaudeStart(CLAUDE_COMMAND, CLAUDE_KIND, launchers.claude?.toString(), on, on, why)
    }

    /** A head of splice.toml whose command link of splice's is still in the install's bin folder. */
    private fun head(fact: JsonObject): ClaudeStart? {
        val command = JsonScalars.str(fact, "command") ?: return null
        val link = links.own(command)?.takeIf { command != CLAUDE_COMMAND } ?: return null
        val gateway = JsonScalars.str(fact, "gateway") == "true"
        val why = if (gateway) null else "forwards your own Claude login, which the Splice gateway never carries"
        return ClaudeStart(command, HEAD, link.toString(), gateway, gateway, why)
    }

    private fun launcher(found: Launcher, ownList: Boolean): ClaudeStart {
        val why = found.why
            ?: found.configDir?.takeIf { ownList }?.let { "its CLAUDE_CONFIG_DIR $it holds a model list" }
        val name = found.path.fileName.toString()
        return ClaudeStart(name, LAUNCHER_KIND, found.path.toString(), found.gateway, found.gateway && !ownList, why)
    }

    /** The model lists of a Claude folder that is not splice's: its settings and its local settings. Its state is
     *  Claude Code's own, which caches what discovery found. */
    private fun folderLists(folder: Path): List<FoundList> =
        ClaudeModelLists.settingsFiles.mapNotNull { lists(folder.resolve(it), false) }

    private fun lists(file: Path, splice: Boolean): FoundList? {
        val read = ClaudeModelLists.read(file) ?: return null
        val state = file.fileName.toString() == STATE_FILE
        val keys = if (state) ClaudeModelLists.inState(read) else ClaudeModelLists.inSettings(read)
        return FoundList(file, keys, splice).takeIf { keys.isNotEmpty() }
    }
}
