// Conservative stale/dead entry detection for Antigravity's `~/.gemini/config/mcp_config.json`.
//
// Heuristic (strictly preserves valid user-authored agy-only servers):
//   1. Disabled user entries (`disabled: true` / `enabled: false`) are never pruned.
//   2. Local stdio entries are dead ONLY when:
//        (a) `command` is an explicit absolute path (no `$` template) that is positively confirmed
//            absent (`Files.notExists`, so `EACCES` / unmounted paths are never pruned), OR
//        (b) `command` is a known script runner (node, bun, deno, python, python3, sh, bash, zsh,
//            ruby, perl, php, tsx, ts-node) and its first script argument is an explicit absolute
//            local file path (no `$` template, no preceding inline-eval/stdin shell flag such as
//            `-e`, `-c`, `-lc`, `-ec`, `--command`, `-s`) that is positively confirmed absent.
//      Relative paths (e.g. `ide.js`), bare package names (e.g. `uvx ast-grep-server`), inline
//      shell commands, inaccessible paths, and existing scripts are never pruned.
//   3. HTTP entries are dead ONLY for the Splice-known `temple` service name pointing at a loopback
//      endpoint (`127.0.0.1` / `localhost` / `::1`) where nothing is listening on that port.
//      User-authored remote or loopback HTTP servers under any other name are never pruned.
package splice.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.client.mcp.TEMPLE_MCP_NAME
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files

// why: loopback TCP connect succeeds or returns ECONNREFUSED in <1ms; 200ms bounds any slow reject without stalling sync.
private const val AGY_PORT_PROBE_TIMEOUT_MS = 200

private val POSIX_SHELL_RUNNERS = setOf("sh", "bash", "zsh")

private val SCRIPT_RUNNERS = POSIX_SHELL_RUNNERS + setOf(
    "node",
    "bun",
    "deno",
    "python",
    "python3",
    "ruby",
    "perl",
    "php",
    "tsx",
    "ts-node",
)

private val INLINE_EVAL_FLAGS = setOf("-e", "--eval", "-c", "-m", "-p", "--print")

private val POSIX_INLINE_CLUSTER = Regex("^-[A-Za-z]*[cs][A-Za-z]*$")

private val POSIX_VALUE_FLAGS = setOf("-o", "+o")

private val VALUE_TAKING_FLAGS = setOf(
    "-r",
    "--require",
    "--import",
    "--loader",
    "--env-file",
    "--conditions",
    "-C",
)

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

/** Checks whether a loopback TCP endpoint is currently accepting connections. */
internal fun interface LoopbackPortProbe {
    fun reachable(host: String, port: Int): Boolean
}

/** Identifies demonstrably dead entries in agy's `mcp_config.json` without touching valid user servers. */
internal class AgyMcpStaleEntries(
    private val portProbe: LoopbackPortProbe = LoopbackPortProbe { host, port ->
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), AGY_PORT_PROBE_TIMEOUT_MS) }
            true
        } catch (_: IOException) {
            false
        }
    },
) {
    fun isDead(name: String, entry: JsonObject): Boolean {
        if (isDisabled(entry)) return false
        val command = JsonScalars.str(entry, "command")?.takeIf { it.isNotBlank() }
        val url = JsonScalars.str(entry, "serverUrl")
            ?: JsonScalars.str(entry, "url")
            ?: JsonScalars.str(entry, "httpUrl")
        return when {
            command != null -> isBrokenStdio(command, entry)
            url != null && name == TEMPLE_MCP_NAME -> isUnreachableLoopback(url)
            else -> false
        }
    }

    private fun isBrokenStdio(command: String, entry: JsonObject): Boolean {
        val baseName = commandBaseName(command)
        val args = (entry["args"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str)
        val scriptArg = if (baseName in SCRIPT_RUNNERS) firstScriptArg(baseName, args) else null
        return isMissingAbsolutePath(command) || (scriptArg != null && isMissingAbsolutePath(scriptArg))
    }

    private fun commandBaseName(command: String): String {
        val trimmed = command.trim()
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        val leaf = if (slash >= 0) trimmed.substring(slash + 1) else trimmed
        return leaf.lowercase().removeSuffix(".exe")
    }

    private fun firstScriptArg(runner: String, args: List<String>): String? {
        val posixShell = runner in POSIX_SHELL_RUNNERS
        var skipNext = false
        var optionsEnded = false
        for (arg in args) {
            val step = classifyArg(arg, posixShell, skipNext, optionsEnded)
            when (step) {
                ArgStep.SKIP -> skipNext = false
                ArgStep.SKIP_NEXT -> skipNext = true
                ArgStep.END_OPTIONS -> optionsEnded = true
                ArgStep.INLINE_OR_STDIN -> return null
                ArgStep.SCRIPT -> return arg
            }
        }
        return null
    }

    private fun classifyArg(arg: String, posixShell: Boolean, skipNext: Boolean, optionsEnded: Boolean): ArgStep =
        when {
            skipNext -> ArgStep.SKIP
            optionsEnded -> ArgStep.SCRIPT
            arg == "--" -> ArgStep.END_OPTIONS
            isInlineOrStdinFlag(arg, posixShell) -> ArgStep.INLINE_OR_STDIN
            isValueFlag(arg, posixShell) -> ArgStep.SKIP_NEXT
            isOptionFlag(arg, posixShell) -> ArgStep.SKIP
            else -> ArgStep.SCRIPT
        }

    private fun isValueFlag(arg: String, posixShell: Boolean): Boolean =
        arg in VALUE_TAKING_FLAGS || (posixShell && arg in POSIX_VALUE_FLAGS)

    private fun isOptionFlag(arg: String, posixShell: Boolean): Boolean =
        arg.startsWith("-") || (posixShell && arg.startsWith("+"))

    private fun isInlineOrStdinFlag(arg: String, posixShell: Boolean): Boolean =
        arg in INLINE_EVAL_FLAGS || (posixShell && isPosixInlineOrStdin(arg))

    private fun isPosixInlineOrStdin(arg: String): Boolean =
        arg == "--command" || arg.startsWith("--command=") || POSIX_INLINE_CLUSTER.matches(arg)

    private fun isMissingAbsolutePath(candidate: String): Boolean {
        if (candidate.isBlank() || '$' in candidate) return false
        val path = PathProbe.spelled(candidate) ?: return false
        return path.isAbsolute && Files.notExists(path)
    }

    private fun isUnreachableLoopback(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl)
        val host = uri.host?.lowercase()
        val port = uri.port
        if (host in LOOPBACK_HOSTS && port > 0) {
            val connectHost = host.orEmpty().removePrefix("[").removeSuffix("]")
            !portProbe.reachable(connectHost, port)
        } else {
            false
        }
    } catch (_: URISyntaxException) {
        false
    }

    private fun isDisabled(entry: JsonObject): Boolean =
        (entry["disabled"] as? JsonPrimitive)?.booleanOrNull == true ||
            (entry["enabled"] as? JsonPrimitive)?.booleanOrNull == false

    private enum class ArgStep { SKIP, SKIP_NEXT, END_OPTIONS, INLINE_OR_STDIN, SCRIPT }
}
