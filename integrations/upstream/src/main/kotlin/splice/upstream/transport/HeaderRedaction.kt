// NEW: V4-174 — the ONE rule for which header values a trace may keep. By NAME CLASS, not by a
// list of the headers we happen to send today: a credential header this proxy learns to forward
// next month (a new provider's `x-goog-api-key`, a session cookie) must land redacted with no edit
// here, so anything whose name says auth, key, token, secret, cookie or session is replaced. The
// name itself is kept — an audit needs to see THAT an Authorization header went out, and to which
// host, without ever seeing what it was. Values that are not secrets ride whole: content-type,
// anthropic-version, retry-after, the rate-limit family, x-request-id — the headers an incident
// is diagnosed from.
//
// Fix 4 of the Oct 27 Responses fixes: a turn state (the ChatGPT backend's x-codex-turn-state routing token) and an
// account id (ChatGPT-Account-ID, which every ChatGPT sign-in head now sends) are kept out the same way, and so are
// such headers where a body or a stream carries them as JSON members: the backend's response.metadata event names
// its turn state, and a websocket frame's client_metadata echoes it. A member quoted INSIDE a JSON string goes the
// same way, whatever its nesting: a refusal that quotes the headers it refused carries the member as `\"name\":\"…\"`
// one level in, and the next level doubles each backslash, so the quoting a match found is the quoting it writes back.
package splice.upstream.transport

/** A JSON member name shaped like a header: words joined by '-'. A name spelled with '_' is a JSON field, not a header
 *  (input_tokens, prompt_cache_key), and is never matched. */
private const val HEADER_NAME = """(?:[A-Za-z0-9_.]++-)++[A-Za-z0-9_.]++"""

public object HeaderRedaction {
    public const val REDACTED: String = "[redacted]"

    private val secretName = Regex(
        "auth|key|token|secret|cookie|session|credential|signature|password|turn-state|account",
        RegexOption.IGNORE_CASE,
    )

    /** A header-named JSON member with a string value (one the text's cut may have cut short) or an array of them.
     *  Possessive throughout, so a long value is scanned once. */
    private val headerMember = Regex(
        """"($HEADER_NAME)"(\s*+:\s*+)("(?:[^"\\]++|\\.)*+(?:"|\z)|\[[^\]]*+(?:]|\z))""",
    )

    /** The same member quoted inside a JSON string: every one of its four quotes carries the same run of backslashes,
     *  and its value runs to the next quote at that same nesting (or to the text's cut). */
    private val nestedHeaderMember = Regex(
        """(\\++)"($HEADER_NAME)\1"(\s*+:\s*+)\1"(?:(?!\1").)*+(?:\1"|\z)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** True when a header of this name may carry a credential. */
    public fun isSecret(name: String): Boolean = secretName.containsMatchIn(name)

    /** The same map, every secret-named value replaced by [REDACTED]. Names and order are kept. */
    public fun redact(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) -> if (isSecret(name)) REDACTED else value }

    /** [text], a body or a stream as it went over the wire, with the value of every JSON member named like a secret
     *  header replaced by [REDACTED], at the top level and inside any quoted string. The member's name is kept, and
     *  everything else in the text is untouched. */
    public fun redactText(text: String): String {
        val plain = headerMember.replace(text) { member ->
            val (name, colon) = member.destructured
            if (isSecret(name)) "\"$name\"$colon\"$REDACTED\"" else member.value
        }
        return nestedHeaderMember.replace(plain) { member ->
            val (slashes, name, colon) = member.destructured
            if (isSecret(name)) "$slashes\"$name$slashes\"$colon$slashes\"$REDACTED$slashes\"" else member.value
        }
    }
}
