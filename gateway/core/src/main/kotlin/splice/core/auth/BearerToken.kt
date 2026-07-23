// NEW: the ONE bearer-scheme parser. Control-plane (MgmtKey.matchesBearer) and inference
// (HeadServer.authorize) must accept identical Authorization bytes; each carried its own copy of
// this regex and they drifted once already — the control plane rejected lowercase `bearer` until
// 2026-07-22 because only the inference copy was case-insensitive.
package splice.core.auth

private val BEARER = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)

/** The token after a case-insensitive `Bearer` scheme, trimmed; null when [header] isn't bearer-shaped. */
public fun bearerToken(header: String?): String? =
    BEARER.find(header.orEmpty().trim())
        ?.groupValues
        ?.get(1)
        ?.trim()
