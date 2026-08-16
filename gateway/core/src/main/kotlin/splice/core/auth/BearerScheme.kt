// NEW: the ONE bearer-scheme parser. Control-plane (MgmtKey.matchesBearer) and inference
// (HeadServer.authorize) must accept identical Authorization bytes; each carried its own copy of
// this regex and they drifted once already — the control plane rejected lowercase `bearer` until
// 2026-07-22 because only the inference copy was case-insensitive.
// Named object since the 2026-08-16 style migration (HD-M8): the parser reads a raw header String
// and there is no splice type to hang it on. Same name, same regex, same trimming.
package splice.core.auth

// FILE SCOPE ON PURPOSE: one compiled Regex for the process, as it was before the migration — a
// member would recompile it per instance if this ever stopped being a singleton.
private val BEARER = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)

public object BearerScheme {

    /** The token after a case-insensitive `Bearer` scheme, trimmed; null when [header] isn't bearer-shaped. */
    public fun bearerToken(header: String?): String? =
        BEARER.find(header.orEmpty().trim())
            ?.groupValues
            ?.get(1)
            ?.trim()
}
