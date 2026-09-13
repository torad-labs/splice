// NEW: v0.4.0 FEATURES.md §6 — the one redaction pass every free-text field of `splice doctor
// --json` goes through — check details, URL hosts, paths, and (with --with-logs) daemon.log lines.
// Structured fields are ALLOWLISTED in DoctorReport (a field that is not named is not emitted);
// this class handles the text that cannot be allowlisted: it masks credential shapes (bearer and
// JWT tokens, key=value secrets, provider keys, long opaque tokens), e-mail addresses, and the
// home directory. Log lines that do not carry the daemon's `[date time] [tag]` prefix are dropped
// whole: the daemon never writes conversation text, so an unshaped line is foreign content.
package splice.app.cli

import java.net.URI
import java.nio.file.Path

internal class DoctorRedaction(private val home: Path) {

    private val jwt = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val bearer = Regex("(?i)\\bbearer\\s+\\S+")
    private val keyValue = Regex(
        "(?i)\\b(api[_-]?key|token|secret|password|passwd|authorization|x-api-key|refresh_token|access_token)" +
            "(\\s*[=:]\\s*)\\S+",
    )
    private val providerKey = Regex("\\b(sk|xai|gsk|xoxb|ghp|github_pat)[-_][A-Za-z0-9_-]{8,}")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val opaque = Regex("\\b[A-Za-z0-9_-]{40,}\\b")
    private val daemonLine = Regex("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}] \\[")

    /** Free text: home shown as `~`, every credential shape and e-mail masked, in that order. */
    fun text(value: String): String = value
        .replace(home.toString(), "~")
        .replace(jwt, "<redacted:jwt>")
        .replace(bearer, "Bearer <redacted>")
        .replace(keyValue) { it.groupValues[1] + it.groupValues[2] + "<redacted>" }
        .replace(providerKey, "<redacted:key>")
        .replace(email, "<redacted:email>")
        .replace(opaque, "<redacted:token>")

    /** A path relative to the home directory when it lies under it. */
    fun path(value: Path): String = text(value.toString())

    /** Only the host of a URL — never userinfo, path or query, which is where credentials ride. */
    fun host(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "<unparsable>"

    /** Daemon-shaped lines only, each redacted and capped; anything else is not a daemon line. */
    fun logLines(lines: List<String>): List<String> = lines
        .filter { daemonLine.containsMatchIn(it) }
        .map { text(it).take(MAX_LINE_CHARS) }
}

private const val MAX_LINE_CHARS = 400
