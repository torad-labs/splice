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
package splice.diagnostics.doctor.report

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.net.URI
import java.nio.file.Path

/** Paths the report may name: splice's own config, install and cache dirs (its state and log dirs
 *  arrive from StatePaths, never as a literal), Claude Code's, the local runtimes', the system's
 *  binaries and config, and the daemon's own URL routes. Everything else (a session cwd, a project
 *  checkout, a scratch directory, a private file) is masked whole. */
private val ALLOWED_PATH_PREFIXES = listOf(
    "~/.config/splice", "~/.local/share/splice", "~/.local/bin", "~/.cache/splice",
    "~/.claude", "~/.lmstudio", "~/.ollama", "/usr", "/etc", "/api", "/health", "/v1", "/launch", "/statusline",
)

/** What [DoctorRedaction.host] reports for a URL that does not parse — the outcome the parse
 *  failure is classified into, since the URL itself is never echoed. */
private const val UNPARSABLE_HOST = "<unparsable>"

internal class DoctorRedaction(private val home: Path, spliceDirs: List<Path> = emptyList()) {
    private val events = DoctorLogEvents()

    private val allowedPrefixes = ALLOWED_PATH_PREFIXES + spliceDirs.map { it.toString().replace(home.toString(), "~") }

    private val jwt = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val bearer = Regex("(?i)\\bbearer\\s+\\S+")

    // The key may be JSON-quoted ("refresh_token": "...") and the value quoted; the quotes and the
    // separator are kept, the value is masked up to the next whitespace or quote (review 2026-09-14).
    private val keyValue = Regex(
        "(?i)\\b([a-z0-9_-]*(?:key|token|secret|password|passwd|pwd|cookie|signature|credential|authorization)" +
            "[a-z0-9_-]*)(\"?\\s*[=:]\\s*\"?)[^\\s\"']+",
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

    /** Only the host of a URL — never userinfo, path or query, which is where credentials ride — and
     *  through the same shape pass as every other string: a key- or UUID-shaped label (a per-tenant
     *  endpoint) is masked like the token it is (review 2026-09-14). */
    fun host(url: String): String =
        Cancellables.runCatchingCancellable { URI.create(url).host }
            .fold(
                onSuccess = { host -> host?.takeIf { it.isNotBlank() }?.let(::text) ?: UNPARSABLE_HOST },
                onFailure = { UNPARSABLE_HOST },
            )

    /** Daemon EVENTS only, each reduced to its structure; every other line is counted, not shown. */
    fun logLines(lines: List<String>, names: SafeNames): LogSelection = events.select(lines, names)
}
