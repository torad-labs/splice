// NEW: v0.4.0 FEATURES.md §6 — the one redaction pass every free-text field of `splice doctor
// --json` goes through, and the two allowlists that make prose impossible to smuggle: a daemon
// log line is emitted as its STRUCTURE only — timestamp, tags, one of the daemon's own EVENT
// HEADS, and the key=value pairs whose KEYS are the daemon's own telemetry vocabulary (the perf
// keys plus a short list) and whose values are numbers, booleans or safe tokens; the free-form
// words after the head, where prose would ride — key=value shaped or not — are never emitted,
// and a line with no event head (conversation text
// appended to a log, a stack frame, a foreign writer) is dropped and counted; a path survives
// only under splice's own directories or the system's. What remains still passes the shape pass:
// credential shapes (bearer and JWT tokens, key=value secrets, provider keys, long opaque tokens),
// e-mail addresses and UUID-shaped ids are masked, the home directory reads as ~.
package splice.app.cli

import splice.core.util.SafeFailureText
import java.net.URI
import java.nio.file.Path

private const val MAX_LINE_CHARS = 400

/** Paths the report may name: splice's own config, install and cache dirs (its state and log dirs
 *  arrive from StatePaths, never as a literal), Claude Code's, the local runtimes', the system's
 *  binaries and config, and the daemon's own URL routes. Everything else (a session cwd, a project
 *  checkout, a scratch directory, a private file) is masked whole. */
private val ALLOWED_PATH_PREFIXES = listOf(
    "~/.config/splice", "~/.local/share/splice", "~/.local/bin", "~/.cache/splice",
    "~/.claude", "~/.lmstudio", "~/.ollama", "/usr", "/etc", "/api", "/health", "/v1", "/launch", "/statusline",
)

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

internal class DoctorRedaction(private val home: Path, spliceDirs: List<Path> = emptyList()) {

    private val allowedPrefixes = ALLOWED_PATH_PREFIXES + spliceDirs.map { it.toString().replace(home.toString(), "~") }

    private val jwt = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val bearer = Regex("(?i)\\bbearer\\s+\\S+")
    private val keyValue = Regex(
        "(?i)\\b([a-z0-9_-]*(?:key|token|secret|password|passwd|pwd|cookie|signature|credential|authorization)" +
            "[a-z0-9_-]*)(\\s*[=:]\\s*)\\S+",
    )
    private val providerKey = Regex("\\b(sk|xai|gsk|xoxb|ghp|github_pat)[-_][A-Za-z0-9_-]{8,}")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val uuid = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
    private val opaque = Regex("\\b[A-Za-z0-9_-]{40,}\\b")

    /** A path and, after it, every word up to the next delimiter or the next path start: a foreign
     *  path may carry spaces ("Secret Merger", "Secret Merger/client"), and nothing in free text
     *  says where such a name ends, so the whole remainder of the value is masked with it. An
     *  allowed path keeps its remainder. The leading path decides the verdict. */
    private val pathToken = Regex(
        "(?<=^|[\\s\"'(=:,\\[])((?:~|/)[^\\s\"'),;\\]]*)((?:\\s+[^\\s\"'),;\\]/~][^\\s\"'),;\\]]*)*)",
    )
    private val daemonLine = Regex("^(\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}]) ((?:\\[[^\\]]*])+) (.*)$")
    private val tag = Regex("\\[([^\\]]*)]")
    private val pair = Regex("^([A-Za-z_][A-Za-z0-9_.-]{0,40})=([^\\s,;)\\]]+)$")

    /** Free text: home shown as `~`, every credential shape, e-mail, UUID id and foreign path masked. */
    fun text(value: String): String = value
        .replace(home.toString(), "~")
        .replace(jwt, "<redacted:jwt>")
        .replace(bearer, "Bearer <redacted>")
        .replace(keyValue) { it.groupValues[1] + it.groupValues[2] + "<redacted>" }
        .replace(providerKey, "<redacted:key>")
        .replace(email, "<redacted:email>")
        .replace(uuid, "<redacted:id>")
        .replace(opaque, "<redacted:token>")
        .replace(pathToken) { m -> if (allowedPath(m.groupValues[1])) m.value else "<redacted:path>" }

    private fun allowedPath(path: String): Boolean =
        path == "/" || path == "~" || allowedPrefixes.any { path == it || path.startsWith("$it/") }

    /** A rendered failure (SafeFailureText already drops messages that quote bytes; the path a
     *  FileSystemException names still goes through the path allowlist here). */
    fun failure(e: Throwable): String = text(SafeFailureText.render(e))

    /** Only the host of a URL — never userinfo, path or query, which is where credentials ride. */
    fun host(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "<unparsable>"

    /** Daemon EVENTS only, each reduced to its structure; every other line is counted, not shown. */
    fun logLines(lines: List<String>, names: SafeNames): LogSelection {
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
