// NEW: labels only (Eli's ruling via splice-lead, 2026-10-12). Every account row is named by a label a person chose or
// by splice's default, never by an email or an account id; this is the one rule every row kind is named by.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.core.auth.AccountRef

// why: what a default reads when neither the provider's plan nor anything else names the account's kind.
private const val FALLBACK_WORD = "Account"

/** What a row is called, its [ref] when its credential proved one, and whether the name is splice's default rather
 *  than one a person gave (a default is what the console offers to name once). */
internal data class AccountName(val shown: String, val ref: String?, val isDefault: Boolean) {
    fun write(into: JsonObjectBuilder) {
        into.put("display_name", shown)
        into.put("label_default", isDefault)
        into.put("ref", ref)
    }
}

/** The facts a row's name is read from: the name a person gave, the label splice stored it under, the [ref]
 *  its credential proved, and the [plan] its provider reported. */
internal data class AccountNameFacts(
    val given: String?,
    val stored: String?,
    val ref: String?,
    val plan: String?,
)

/** [chosen] is the names a person gave, by ref (AccountNameStore): read first, for every kind of login. */
internal class AccountNaming(private val chosen: Map<String, String> = emptyMap()) {
    private val hashTail = Regex("-([0-9a-f]{${AccountRef.CHARS}})$")

    // why: the ordinal splice mints when no name was given (`claude-2`, `codex-3`, `grok-2`), whatever the vendor.
    private val ordinal = Regex("^[a-z][a-z0-9]*-\\d+$")

    /** A name chosen on the console wins, then a name a person gave elsewhere, then a stored label a person chose;
     *  anything else reads `<Plan> · <ref>`. A stored label splice made up itself (its ordinal `claude-2`, ChatGPT's
     *  `plus-<ref>`, the first account's `primary`) is a default too, so it is offered for naming and never shown as if
     *  someone chose it. */
    fun name(facts: AccountNameFacts): AccountName {
        val ref = facts.ref ?: facts.stored?.let { hashTail.find(it)?.groupValues?.get(1) }
        val given = ref?.let(chosen::get) ?: facts.given?.takeIf(String::isNotBlank) ?: personal(facts.stored)
        return given?.let { AccountName(it, ref, isDefault = false) } ?: fallback(facts.plan, ref)
    }

    /** A stored label a person chose, or null for a blank one or one splice made up. */
    private fun personal(stored: String?): String? = stored?.takeIf { it.isNotBlank() && !madeUp(it) }

    private fun fallback(plan: String?, ref: String?): AccountName {
        val word = plan?.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercaseChar) ?: FALLBACK_WORD
        return AccountName(ref?.let { "$word · $it" } ?: word, ref, isDefault = true)
    }

    private fun madeUp(label: String): Boolean =
        label == PRIMARY_ID || hashTail.containsMatchIn(label) || ordinal.matches(label)
}
