// NEW: head-catalog (gw-check). What `splice claude check` finds: every way Claude Code starts on this machine and
// whether each goes through the Splice gateway and gets its list, and every model list that would change a session's
// list. `claude` and the heads are read from the daemon, which says how each command launches now without launching
// it (HeadRetirement.launchFacts); a head's command counts while splice's link for it is in the install's bin folder.
// The launchers on PATH and the model lists are read from the files: a head's folder (splice's, which retiring
// removes), the person's own ~/.claude settings, every CLAUDE_CONFIG_DIR a launcher names, and the managed settings,
// which are reported and never edited.
package splice.app.cli.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path

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

// What a start is called plainly, and the kinds `splice claude check` reports.
internal const val CLAUDE_KIND = "claude"
internal const val HEAD = "head"
internal const val LAUNCHER_KIND = "launcher"

// Claude Code's own state file, which caches what discovery found.
internal const val STATE_FILE = ".claude.json"
