// NEW: V4-160 — the transcript text as a page shows it, moved verbatim out of TranscriptReader.kt
// (concentration, 2026-09-18).
package splice.core.sessions

/** Text kept per message. A tool result can be megabytes; the text says where it was cut. */
private const val MAX_TEXT_CHARS = 64 shl 10

/** Credential shapes only (the doctor report's set, DoctorRedaction): this is the operator's own
 *  conversation, so paths, ids and addresses stay readable and only secrets are masked. */
internal class TranscriptRedaction {
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val bearer = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val keyValue = Regex(
        "(?i)\\b([a-z0-9_-]*(?:api_?key|token|secret|password|passwd|cookie|credential|authorization)" +
            "[a-z0-9_-]*)(\"?\\s*[=:]\\s*\"?)[^\\s\"',}]{8,}",
    )
    private val providerKey = Regex("\\b(sk|xai|gsk|xoxb|ghp|github_pat)[-_][A-Za-z0-9_-]{16,}")

    /** [value] as a page shows it: clipped to MAX_TEXT_CHARS, then redacted. */
    fun shown(value: String): String = text(clip(value))

    private fun clip(text: String): String =
        if (text.length <= MAX_TEXT_CHARS) {
            text
        } else {
            text.take(MAX_TEXT_CHARS) + "\n… [cut: ${text.length - MAX_TEXT_CHARS} more characters]"
        }

    fun text(value: String): String = value
        .replace(jwt, "[redacted jwt]")
        .replace(bearer, "Bearer [redacted]")
        .replace(keyValue) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        .replace(providerKey, "[redacted key]")
}
