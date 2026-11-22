// NEW: head-catalog (gw-check). What `splice claude check` finds: every way Claude Code starts on this machine and
// whether each goes through the Splice gateway and gets its list, and every model list that would change a session's
// list. `claude` and the heads are read from the daemon, which says how each command launches now without launching
// it (HeadRetirement.launchFacts); a head's command counts while splice's link for it is in the install's bin folder.
// The launchers on PATH and the model lists are read from the files: a head's folder (splice's, which retiring
// removes), the person's own ~/.claude settings, every CLAUDE_CONFIG_DIR a launcher names, and the managed settings,
// which are reported and never edited.
package splice.app.cli.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.head.WrapperBinSource
import splice.app.head.WrapperLinks
import splice.client.ClaudeModelLists
import splice.core.config.UserHome
import splice.core.topology.CLAUDE_COMMAND
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Path
import java.nio.file.Paths

/** One way Claude Code starts: [kind] `claude`, `head` or `launcher`, where it is ([path]), whether it goes through
 *  the Splice [gateway], whether its session's list is the gateway's ([gatewayList]), and [why] when it is not. */
internal data class ClaudeStart(
    val command: String,
    val kind: String,
    val path: String?,
    val gateway: Boolean,
    val gatewayList: Boolean,
    val why: String?,
)

/** One model list found: the file, the [keys] that lay it, and whether it is [splice]'s (retiring removes it). */
internal data class FoundList(val path: Path, val keys: List<String>, val splice: Boolean)

internal class ClaudeStarts(
    val starts: List<ClaudeStart>,
    val lists: List<FoundList>,
) {
    /** Every start that is not `claude` is gone or goes through the gateway, `claude` is on it, and no head of
     *  splice's is left. */
    val oneWay: Boolean get() = starts.all { it.gateway } && starts.none { it.kind == HEAD }

    /** Every start's list is the gateway's, and no model list is found. */
    val oneList: Boolean get() = starts.all { it.gatewayList } && lists.isEmpty()

    fun json(): JsonObject = buildJsonObject {
        put("one_way", oneWay)
        put("one_list", oneList)
        putJsonArray("starts") {
            starts.forEach { start ->
                addJsonObject {
                    put("command", start.command)
                    put("kind", start.kind)
                    put("path", start.path)
                    put("gateway", start.gateway)
                    put("gateway_list", start.gatewayList)
                    put("why", start.why)
                }
            }
        }
        putJsonArray("lists") {
            lists.forEach { found ->
                addJsonObject {
                    put("path", found.path.toString())
                    putJsonArray("keys") { found.keys.forEach { add(JsonPrimitive(it)) } }
                    put("splice", found.splice)
                }
            }
        }
    }

    /** The same, in plain lines. */
    fun lines(): List<String> = buildList {
        add("how Claude Code starts here:")
        starts.forEach { add("  ${startSaid(it)}") }
        if (lists.isNotEmpty()) add("model lists that change a session's list:")
        lists.forEach { found ->
            val whose = if (found.splice) "splice's: retiring its head removes it" else "yours: reported, never edited"
            add("  ${found.path}: ${found.keys.joinToString(", ")} ($whose)")
        }
        add(verdict())
    }

    private fun startSaid(start: ClaudeStart): String {
        val where = listOfNotNull(start.kind.takeIf { it != CLAUDE_KIND }, start.path).joinToString(", ")
        val name = if (where.isEmpty()) "`${start.command}`" else "`${start.command}` ($where)"
        val route = if (start.gateway) "through the Splice gateway" else "past the Splice gateway"
        val list = if (start.gatewayList) "with the Splice gateway's list" else "with a list of its own"
        return "$name: $route, $list" + (start.why?.let { ": $it" } ?: "")
    }

    private fun verdict(): String = if (oneWay && oneList) {
        "one way, one list: `claude` on the Splice gateway"
    } else {
        "not yet one way, one list: ${inTheWay()}"
    }

    /** What stands between this machine and one way, one list. */
    private fun inTheWay(): String {
        val heads = named { it.kind == HEAD }
        val past = named { !it.gateway && it.kind != HEAD }
        val own = named { it.gateway && !it.gatewayList }
        return listOfNotNull(
            heads?.let { "custom head commands $it (deprecated: `splice claude retire --all` retires them)" },
            past?.let { "$it past the Splice gateway" },
            own?.let { "$it with a list of its own" },
            "${lists.size} model list(s) beside the Splice gateway's".takeIf { lists.isNotEmpty() },
        ).joinToString("; ")
    }

    /** The starts [which] picks, named, or null when it picks none. */
    private fun named(which: StartPick): String? =
        starts.filter { which(it) }.map { "`${it.path ?: it.command}`" }.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** Which starts a line of the verdict names. */
internal fun interface StartPick {
    operator fun invoke(start: ClaudeStart): Boolean
}

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
            listOf(Paths.get(folder).resolve(SETTINGS), Paths.get(folder).resolve(STATE)).mapNotNull { lists(it, true) }
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
        val on = lane == DOOR
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
        return ClaudeStart(name, LAUNCHER, found.path.toString(), found.gateway, found.gateway && !ownList, why)
    }

    /** The model lists of a Claude folder that is not splice's: its settings and its local settings. Its state is
     *  Claude Code's own, which caches what discovery found. */
    private fun folderLists(folder: Path): List<FoundList> =
        ClaudeModelLists.settingsFiles.mapNotNull { lists(folder.resolve(it), false) }

    private fun lists(file: Path, splice: Boolean): FoundList? {
        val read = ClaudeModelLists.read(file) ?: return null
        val state = file.fileName.toString() == STATE
        val keys = if (state) ClaudeModelLists.inState(read) else ClaudeModelLists.inSettings(read)
        return FoundList(file, keys, splice).takeIf { keys.isNotEmpty() }
    }
}

private const val CLAUDE_KIND = "claude"
private const val HEAD = "head"
private const val LAUNCHER = "launcher"
private const val DOOR = "door"
private const val SETTINGS = "settings.json"
private const val STATE = ".claude.json"
