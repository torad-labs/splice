// NEW: LAN share (docs/specs/lan-share.md). A login splice holds for a Claude head is an OAuth login, and Anthropic
// serves an OAuth login only on a request that names the OAuth beta. A caller signed in with OAuth names it itself; a
// node's Claude Code, launched on a word that is no login, names none. So the held login names it on every turn it
// carries, beside the caller's own flags (ListValuedHeaders joins the two lists).
package splice.app.auth.claude

import splice.core.auth.Credentials
import splice.upstream.CredentialHeaders

/** The headers a held Claude login adds to each turn it carries. */
internal object ClaudeLoginHeaders : CredentialHeaders {
    override suspend fun invoke(credentials: Credentials): Map<String, String> = mapOf(BETA_HEADER to OAUTH_BETA)
}

private const val BETA_HEADER = "anthropic-beta"

// why: the beta flag Anthropic requires on a request signed with a Claude OAuth token; the usage probe names the same.
private const val OAUTH_BETA = "oauth-2025-04-20"
