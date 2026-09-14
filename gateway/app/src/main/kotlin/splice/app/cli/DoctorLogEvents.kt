// NEW: v0.4.0 FEATURES.md §6 — the doctor report's log-line reducer: which daemon.log lines are
// events, and what of each survives (timestamp, aliased tags, the event head, the daemon's own
// key=value pairs). Split from DoctorRedaction.kt (concentration, 2026-09-14).
package splice.app.cli

private const val MAX_LINE_CHARS = 400

/** The daemon's event heads: how each family of daemon.log message begins (derived from the
 *  daemon's log call sites and its live log). A message that opens with none of them is not
 *  one of the daemon's events, whatever its prefix says. */
private val EVENT_HEADS: Regex = listOf(
    "compact=(true|false)", "cache: ", "perf ", "turn (compact=|ERROR |cancelled)",
    "row '", "activity label", "count_tokens ", "tool search round", "socket (stream|closed|failed)",
    "websocket round", "frame arrived", "rate-limit cooldown", "fold (round|re-anchor)", "upstream \\d+ attempt",
    "re-anchor \\d+:", "transport(-possible-duplicate)? ", "mid-stream ",
    "empty-(message|turn)", "client gone", "tcp_nodelay\\(", "launch ", "up: control", "head ",
    "control ", "detached compaction", "promote-to-text", "compaction (retry|answer)", "inbox (overflow|closed)",
    "UNCAUGHT on main", "silent \\d+", "dangerouslySkipPermissions engaged", "abandoned record",
    "ws-[0-9a-f]+ (chained|busy|connected|full send|connect failed|no first|frames arrived|inbox closed|send failed)",
    "failed ", "ignoring ", "waited ", "stale-400", "client-window", "sessions ", "abort", "fragmented", "frame ",
    "unexpected", "unparseable", "boot ", "probe ", "quota ", "usage ", "account ", "switch", "local runtime",
    "upgrade ", "refresh", "credential", "materialized", "linked", "stopping", "stopped", "shutdown",
).joinToString("|", prefix = "^(", postfix = ")").toRegex()

/** The MCP host's events: `[mcp-host] <server>: <event>`, where the server is an operator-authored
 *  name (aliased like any other) and the event opens with one of these (HostedServer's log calls). */
private val MCP_EVENT_HEADS: Regex = listOf(
    "hosted as pid", "closing pid", "pid \\d+ exited", "keeps crashing", "spawn failed", "did not complete",
    "rejected the MCP handshake", "no initialize answer", "handshake failed", "cancelled during the handshake",
    "exited", "evicted", "idle for", "closed", "replaced", "not running",
).joinToString("|", prefix = "^(", postfix = ")").toRegex()

/** The keys an event may carry: the perf keys plus the daemon's own counters and identities. A key
 *  outside this vocabulary is not the daemon's, whatever follows its `=`. */
private val LOG_PAIR_KEYS: Set<String> = perfNumericFields + setOf(
    "outcome", "model", "compact", "has_marker", "tool_count", "input", "cached", "generation", "attempt",
    "status", "port", "n",
)
private val IDENTITY_PAIR_KEYS: Set<String> = setOf("outcome", "model")
private val NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$")
private val WHITESPACE = Regex("\\s+")

/** The lines a log tail keeps, and how many it dropped for not being daemon events. */
internal data class LogSelection(val kept: List<String>, val dropped: Int)

internal class DoctorLogEvents {
    private val daemonLine = Regex("^(\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}]) ((?:\\[[^\\]]*])+) (.*)$")
    private val tag = Regex("\\[([^\\]]*)]")
    private val pair = Regex("^([A-Za-z_][A-Za-z0-9_.-]{0,40})=([^\\s,;)\\]]+)$")

    /** Daemon EVENTS only, each reduced to its structure; every other line is counted, not shown. */
    fun select(lines: List<String>, names: SafeNames): LogSelection {
        val kept = lines.mapNotNull { line -> event(line, names) }
        return LogSelection(kept, lines.size - kept.size)
    }

    /** Timestamp, tags (aliased like any operator-authored name), the event head, and the key=value
     *  pairs the daemon AUTHORED: the unbroken run of pairs that directly follows the head, whose keys
     *  are the daemon's vocabulary and whose values are numbers, booleans or (for the identity keys)
     *  safe tokens. The run ends at the first word that is not a pair, so an upstream echo or any
     *  other free text later in the line can never contribute a pair. */
    private fun event(line: String, names: SafeNames): String? {
        val shaped = daemonLine.find(line) ?: return null
        val (stamp, tags, message) = shaped.destructured
        val safeTags = tag.findAll(tags).joinToString("") { "[${names.head(it.groupValues[1])}]" }
        return if (tags.contains("[mcp-host]")) {
            mcpEvent(stamp, safeTags, message, names)
        } else {
            daemonEvent(stamp, safeTags, message, names)
        }
    }

    private fun daemonEvent(stamp: String, safeTags: String, message: String, names: SafeNames): String? {
        val head = EVENT_HEADS.find(message) ?: return null
        val pairs = message.substring(head.range.last + 1).trim().split(WHITESPACE)
            .map { word -> pair.find(word) }
            .takeWhile { it != null }
            .mapNotNull { p ->
                val (key, value) = checkNotNull(p).destructured
                pairValue(key, value, names)?.let { "$key=$it" }
            }
        return (listOf(stamp, safeTags, head.value.trimEnd()) + pairs).joinToString(" ").take(MAX_LINE_CHARS)
    }

    /** An MCP host line: the server name as a safe token, then the event head, nothing else (the
     *  pid, the reason and the child's own words stay out). Dropped before, so a report of a hosting
     *  problem carried no hosting line at all (review 2026-09-14). */
    private fun mcpEvent(stamp: String, safeTags: String, message: String, names: SafeNames): String? {
        val server = message.substringBefore(": ", "")
        val head = MCP_EVENT_HEADS.find(message.substringAfter(": ", "")) ?: return null
        val parts = listOf(stamp, safeTags, names.token(server) + ":", head.value.trimEnd())
        return parts.joinToString(" ").take(MAX_LINE_CHARS)
    }

    private fun pairValue(key: String, value: String, names: SafeNames): String? = when {
        key !in LOG_PAIR_KEYS -> null
        NUMBER.matches(value) || value == "true" || value == "false" -> value
        key in IDENTITY_PAIR_KEYS -> names.token(value)
        else -> null
    }
}
