// NEW: Oct 10, 2026 — what the accounts list knows of a command's first OAuth account beyond its credential, so its row
// can be renamed and removed like the accounts added after it (BUILD row "Accounts: rename or remove an account").
package splice.accounts.pool

/** The OAuth file layer's answers for a first account, by its auth kind and credential path. */
public interface FirstAccounts {
    /** The name a person gave it, or null when it has none. */
    public fun name(kind: String, path: String): String?

    /** Whether its file is shared with the vendor's own CLI, which keeps it from being removed here. */
    public fun shared(kind: String, path: String): Boolean
}
