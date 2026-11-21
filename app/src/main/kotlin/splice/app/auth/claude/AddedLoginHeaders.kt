// NEW: door-default (docs/specs/door-default.md), the ruling of Nov 17, 2026. A Claude head forwards the
// caller's own login, so splice held no credential to ask Anthropic what it serves, and a head with no list kept
// started with none: the door, which runs Claude's models on the Claude accounts added to splice, then had no Claude
// model to list. The list is asked with an added account's token instead, read from its folder as it is now; the
// pool that serves the account's turns renews that token, and nothing here does.
package splice.app.auth.claude

import splice.models.roster.DeclaredHeads
import splice.usage.quota.CLAUDE_OAUTH_BETA
import java.nio.file.Path

/** The headers that present a Claude account added to a head, for asking what Anthropic serves it. */
internal class AddedLoginHeaders(stateDir: Path, declared: DeclaredHeads = DeclaredHeads { emptyMap() }) {
    private val accounts = ClaudeRouteAccounts(ClaudeAccountFolders(stateDir), declared)
    private val facts = ClaudeLoginFactsReader()

    /** An added account of [head]'s, the first whose folder holds a token, as a request presents it; null with none.
     *  A provider the gateway serves under its own key is asked with an account of a head that serves it
     *  (ClaudeRouteAccounts). */
    fun of(head: String): Map<String, String>? {
        val token = accounts.of(head).firstNotNullOfOrNull { facts.token(it.directory) } ?: return null
        return mapOf(AUTHORIZATION to "Bearer $token", ANTHROPIC_BETA to CLAUDE_OAUTH_BETA)
    }
}

// The headers an OAuth login rides in on Anthropic's API: the bearer, and the beta flag that admits it.
private const val AUTHORIZATION = "Authorization"
private const val ANTHROPIC_BETA = "anthropic-beta"
