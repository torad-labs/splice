// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). What the translator reads from the command
// line Claude Desktop starts `claude` with: the model, the session to take up, the permission mode, the effort and the
// extra folders. A flag is written `--flag=value` or `--flag value`; every other flag Desktop passes (output format,
// settings, allowed tools …) is Claude Code's alone and is passed over. `--stands-in-for=<path>` is the launcher's: the
// Claude Code Desktop meant to start, whose version the session reports, since Desktop refuses a launched program
// that answers with another version than the one it chose.
package splice.provider.antigravity

import splice.core.GATEWAY_VERSION

/** The parts of Desktop's `claude` command line a session on agy uses. */
internal data class ClaudeArgs(
    val model: String?,
    val resume: String?,
    val sessionId: String?,
    val permissionMode: String,
    val effort: String?,
    val addDirs: List<String>,
    val version: String,
)

/** Reads [ClaudeArgs] out of a `claude` command line. */
internal class ClaudeArgsReader {
    fun of(args: List<String>): ClaudeArgs {
        val values = valuesOf(args)
        val last = { name: String -> values[name]?.lastOrNull()?.takeIf(String::isNotBlank) }
        return ClaudeArgs(
            model = last("--model"),
            resume = last("--resume"),
            sessionId = last("--session-id"),
            permissionMode = last("--permission-mode") ?: DEFAULT_MODE,
            effort = last("--effort"),
            addDirs = values[ADD_DIR].orEmpty().filter(String::isNotBlank),
            version = last(STANDS_IN_FOR)?.split('/')?.lastOrNull(SEMVER::matches) ?: GATEWAY_VERSION,
        )
    }

    /** Each flag of [VALUE_FLAGS] in [args] with the values given it, in order. */
    private fun valuesOf(args: List<String>): Map<String, List<String>> {
        val values = LinkedHashMap<String, MutableList<String>>()
        var at = 0
        while (at < args.size) {
            val arg = args[at++]
            val name = arg.substringBefore('=')
            val list = values.takeIf { name in VALUE_FLAGS }?.getOrPut(name) { mutableListOf() } ?: continue
            at += taken(arg, args, at, list)
        }
        return values
    }

    /** Adds [arg]'s values to [list]: its own after `=`, or the arguments from [next] on that it takes; how many of
     *  those it took. `--add-dir` takes every argument up to the next flag. */
    private fun taken(arg: String, args: List<String>, next: Int, list: MutableList<String>): Int {
        if ('=' in arg) {
            list += arg.substringAfter('=')
            return 0
        }
        val more = if (arg == ADD_DIR) Int.MAX_VALUE else 1
        val values = args.drop(next).take(more).takeWhile { !it.startsWith("-") || arg != ADD_DIR }
        list += values
        return values.size
    }
}

/** Desktop's permission mode when it names none, as Claude Code's own. */
internal const val DEFAULT_MODE: String = "default"

private const val ADD_DIR = "--add-dir"
internal const val STRICT_MCP_CONFIG: String = "--strict-mcp-config"

/** The launcher's flag naming the Claude Code the translator stands in for. */
public const val STANDS_IN_FOR: String = "--stands-in-for"

/** A Claude Code version, as Desktop keeps each in a folder of its own (`…/claude-code/<version>/…`). */
private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")

private val VALUE_FLAGS =
    setOf("--model", "--resume", "--session-id", "--permission-mode", "--effort", ADD_DIR, STANDS_IN_FOR)
