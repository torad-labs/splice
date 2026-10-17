// NEW: the one short, non-reversible name an account goes by outside its vendor module (Eli's ruling via splice-lead,
// 2026-10-12: the console never displays an account's email or account id, labels only).
package splice.core.auth

import splice.core.perf.InputDigest

/** The key an account's [AccountRef] rides under in [AuthDescription.fields]. A vendor module writes it from the id it
 *  reads; nothing past that module ever sees the id itself. */
public const val ACCOUNT_REF_FIELD: String = "account_ref"

/** An account's ref: the first 8 hex characters of SHA-256 over its vendor account id. One length for every vendor,
 *  and the same hash ChatGPT's stored default labels already end in, so a stored `plus-1a2b3c4d` names the account
 *  whose ref is `1a2b3c4d`. Two rows with one ref are one account; the id cannot be read back from it. */
public object AccountRef {
    // why: 8 hex (32 bits) tells a person's few logins apart at a glance, and ChatGPT's stored
    // `plus-<ref>` labels already end in it
    public const val CHARS: Int = 8

    public fun of(accountId: String): String = InputDigest.hex(accountId).take(CHARS)
}
