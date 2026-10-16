// NEW: a quota reading names its account, and an account keeps its own file (Marcos, Oct 15, 2026: "usage tracks the
// wrong account"). A head's file holds whichever account its credential named at the last reading, so a sign-in moved
// to another account overwrote the first one's windows and left nothing saying whose they were.
package splice.head.usage

import splice.core.auth.AccountRef
import splice.core.auth.AuthProvider
import splice.core.usage.QuotaAccount
import java.nio.file.Path

/** One account's naming: [auth] says which account its credential names at each reading, [label] is the name the
 *  head signs it in under, and [base] is the head's quota file, beside which each account keeps its own:
 *  `codex-quota.json` gives `codex-quota-<ref>.json`. A credential that proves no account names nothing. */
public class QuotaAccountFiles(private val base: Path, private val label: String, private val auth: AuthProvider) {
    private val refShape = Regex("[0-9a-f]{${AccountRef.CHARS}}")

    /** The account the credential names now and its own file, or null when it proves none. */
    public fun current(): Pair<QuotaAccount, Path>? {
        val ref = auth.observedAccountRef()?.takeIf(refShape::matches) ?: return null
        val own = base.resolveSibling("${base.fileName.toString().removeSuffix(".json")}-$ref.json")
        return QuotaAccount(ref, label) to own
    }
}
