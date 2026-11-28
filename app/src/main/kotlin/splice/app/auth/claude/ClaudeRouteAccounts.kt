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
        val heads = declared()
        if (key in heads) return folders.accounts(key)
        folders.accounts(key).takeIf { it.isNotEmpty() }?.let { return it }
        val provider = key.removePrefix(MOVED_BOOKS_PREFIX)
        return heads.asSequence()
            .filter { it.value.provider == provider }
            .map { folders.accounts(it.key) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    /** The static owner key for [key] when it unambiguously maps to one head or its own folder: a declared head owns
     *  itself, a provider key with accounts filed directly under it owns itself, and a provider key with exactly one
     *  declared head serving that provider shares that head's key from boot. When two or more declared heads serve the
     *  provider (and [key] has no folder of its own), returns null so the gateway route follows whichever head holds
     *  accounts dynamically instead of fusing to one head at boot. */
    fun canonicalKey(key: String): String? {
        val heads = declared()
        if (key in heads || folders.accounts(key).isNotEmpty()) return key
        val provider = key.removePrefix(MOVED_BOOKS_PREFIX)
        return heads.filterValues { it.provider == provider }.keys.singleOrNull()
    }

    /** Declared heads serving [key]'s provider when [key] is a gateway provider route without its own folder. */
    fun servingHeads(key: String): Set<String> {
        val heads = declared()
        if (key in heads || folders.accounts(key).isNotEmpty()) return emptySet()
        val provider = key.removePrefix(MOVED_BOOKS_PREFIX)
        return heads.filterValues { it.provider == provider }.keys
    }
}

// The prefix BooksKeys gives a provider's key when a head of that name serves another provider.
private const val MOVED_BOOKS_PREFIX = "providers."
