// NEW: 2026-11-21 — the Claude accounts a route runs on. An added account is filed under the head it was added to
// (`claude-accounts/<head>`). The gateway serves a provider under the provider's own key (BooksKeys), which is no
// head's when the head that serves it has another name (`[heads.claude] provider = "anthropic"`). Read by that key
// alone, the provider had no account: the door left Anthropic out, and the model catalog, gateway discovery and every
// picker lost Claude's models. A provider with no account filed under its own key runs on the accounts of a head that
// serves it.
package splice.app.auth.claude

import splice.models.roster.DeclaredHeads

internal class ClaudeRouteAccounts(
    private val folders: ClaudeAccountFolders,
    private val declared: DeclaredHeads = DeclaredHeads { emptyMap() },
) {
    /** The accounts filed under [key]; for a provider's key with none, those of the first head serving that provider
     *  that has any. A head's own key answers its own accounts only. */
    fun of(key: String): List<ClaudeAccount> {
        val own = folders.accounts(key)
        val heads = declared()
        if (own.isNotEmpty() || key in heads) return own
        val provider = key.removePrefix(MOVED_BOOKS_PREFIX)
        return heads.filterValues { it.provider == provider }.keys
            .firstNotNullOfOrNull { head -> folders.accounts(head).ifEmpty { null } }
            .orEmpty()
    }
}

// The prefix BooksKeys gives a provider's key when a head of that name serves another provider.
private const val MOVED_BOOKS_PREFIX = "providers."
