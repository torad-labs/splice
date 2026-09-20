// NEW: V4-174 — the ONE rule for which header values a trace may keep. By NAME CLASS, not by a
// list of the headers we happen to send today: a credential header this proxy learns to forward
// next month (a new provider's `x-goog-api-key`, a session cookie) must land redacted with no edit
// here, so anything whose name says auth, key, token, secret, cookie or session is replaced. The
// name itself is kept — an audit needs to see THAT an Authorization header went out, and to which
// host, without ever seeing what it was. Values that are not secrets ride whole: content-type,
// anthropic-version, retry-after, the rate-limit family, x-request-id — the headers an incident
// is diagnosed from.
package splice.spi

public object HeaderRedaction {
    public const val REDACTED: String = "[redacted]"

    private val secretName = Regex(
        "auth|key|token|secret|cookie|session|credential|signature|password",
        RegexOption.IGNORE_CASE,
    )

    /** True when a header of this name may carry a credential. */
    public fun isSecret(name: String): Boolean = secretName.containsMatchIn(name)

    /** The same map, every secret-named value replaced by [REDACTED]. Names and order are kept. */
    public fun redact(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) -> if (isSecret(name)) REDACTED else value }
}
