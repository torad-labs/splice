// NEW: the stored number (Marlin via fin, Oct 12). An unnamed login reads its plan word and a number it keeps for good,
// "Max", "Max 2", "Max 3", counted per provider, so no page ever needs its ref to tell two logins apart.
package splice.accounts.pool

import kotlinx.serialization.Serializable

// why: what a login reads before splice knows its plan; a number held under it gives way to a plan word read later,
// so a missing plan reading never fixes a login as "Account".
internal const val FALLBACK_WORD = "Account"

// why: what a login no roster row holds any more reads when it never had a number; it gives way once the login is back.
public const val SIGNED_OUT_WORD: String = "Signed-out account"

/** What an unnamed login is counted among: its [provider] (null where the roster does not know it) and its plan [word]. */
public data class AccountKind(val provider: String?, val word: String)

/** The number a login was given within its kind. The first reads its word alone; later ones add their number. */
@Serializable
internal data class AccountNumber(val provider: String? = null, val word: String, val n: Int) {
    fun shown(): String = if (n == 1) word else "$word $n"
}

/** Hands each login in [kept]'s absence the next free number of its kind and never takes one back. */
internal class AccountNumbers(private val kept: Map<String, AccountNumber>) {
    /** Every number after [kinds] were seen, by ref: the kept ones plus any first sight, in the order given. */
    fun assign(kinds: Map<String, AccountKind>): Map<String, AccountNumber> {
        val next = LinkedHashMap(kept)
        kinds.forEach { (ref, kind) ->
            if (next[ref]?.let { replaced(it, kind) } != false) next[ref] = following(next.values, kind)
        }
        return next
    }

    /** A signed-out number gives way to any word the login reads once it is back; "Account" gives way to a plan. */
    private fun replaced(held: AccountNumber, seen: AccountKind): Boolean = when (held.word) {
        SIGNED_OUT_WORD -> seen.word != SIGNED_OUT_WORD
        FALLBACK_WORD -> seen.word !in setOf(FALLBACK_WORD, SIGNED_OUT_WORD)
        else -> false
    }

    private fun following(taken: Collection<AccountNumber>, kind: AccountKind): AccountNumber {
        val last = taken.filter { it.provider == kind.provider && it.word == kind.word }.maxOfOrNull { it.n } ?: 0
        return AccountNumber(kind.provider, kind.word, last + 1)
    }
}
