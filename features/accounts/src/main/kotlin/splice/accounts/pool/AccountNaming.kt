// Account rows use a chosen label first, then the account email when its provider proves one, then splice's fallback.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import splice.core.auth.AccountRef
import splice.core.util.JsonScalars

private const val MAX_ACCOUNT_EMAIL_CHARS = 254

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
    val email: String? = null,
)

/** [store] holds the names a person gave and the number each unnamed login keeps, by ref (AccountNameStore); without
 *  one, nothing is named beyond what each login carries and numbers last for this roster alone. One instance names one
 *  roster: it notes each default it hands out, so [settle] can give it its number. */
internal class AccountNaming(private val store: AccountNameStore? = null) {
    private val chosen = store?.names().orEmpty()

    // the plan word each unnamed login with a ref was given, by ref, in the order the roster named them
    private val words = LinkedHashMap<String, String>()
    private val hashTail = Regex("-([0-9a-f]{${AccountRef.CHARS}})$")

    // why: the ordinal splice mints when no name was given (`claude-2`, `codex-3`, `grok-2`), whatever the vendor.
    private val ordinal = Regex("^[a-z][a-z0-9]*-\\d+$")

    /** A name chosen on the console wins, then a name a person gave elsewhere, then a stored label a person chose,
     *  then the proved account email; anything else reads its plan word, numbered once the roster settles. A stored label splice made up itself (its ordinal `claude-2`, ChatGPT's
     *  `plus-<ref>`, the first account's `primary`) is a default too, so it is offered for naming and never shown as if
     *  someone chose it. */
    fun name(facts: AccountNameFacts): AccountName {
        val ref = facts.ref ?: facts.stored?.let { hashTail.find(it)?.groupValues?.get(1) }
        val given = ref?.let(chosen::get) ?: facts.given?.takeIf(String::isNotBlank) ?: personal(facts.stored)
        given?.let { return AccountName(it, ref, isDefault = false) }
        return emailName(facts.email, ref) ?: fallback(facts.plan, ref)
    }

    private fun emailName(email: String?, ref: String?): AccountName? {
        val trimmed = email?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_ACCOUNT_EMAIL_CHARS) return null
        return trimmed.takeIf { '@' in it && it.none(Char::isISOControl) }
            ?.let { AccountName(it, ref, isDefault = true) }
    }

    /** A stored label a person chose, or null for a blank one or one splice made up. */
    private fun personal(stored: String?): String? = stored?.takeIf { it.isNotBlank() && !madeUp(it) }

    private fun fallback(plan: String?, ref: String?): AccountName {
        val word = plan?.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercaseChar) ?: FALLBACK_WORD
        ref?.let { words[it] = word }
        return AccountName(word, ref, isDefault = true)
    }

    /** The stored number (Marlin via fin, Oct 12): an unnamed login reads its plan word and the number it keeps within
     *  its provider, "Max" then "Max 2", so no page needs its ref. Numbers are counted per provider, since every page
     *  puts the provider beside the name, and a login [rows] shows for the first time takes the next one. */
    fun settle(rows: List<JsonObject>): List<JsonObject> {
        val kinds = LinkedHashMap<String, AccountKind>()
        rows.forEach { row -> kind(row)?.let { kinds.putIfAbsent(it.first, it.second) } }
        val shown = store?.numbered(kinds) ?: AccountNumbers(emptyMap()).assign(kinds).mapValues { it.value.shown() }
        return rows.map { row ->
            val name = kind(row)?.let { shown[it.first] }
            if (name != null) JsonObject(row + ("display_name" to JsonPrimitive(name))) else row
        }
    }

    /** An unnamed login's ref and the provider and plan word it is counted among, or null for any other row. */
    private fun kind(row: JsonObject): Pair<String, AccountKind>? {
        val ref = JsonScalars.str(row, "ref")?.takeIf { JsonScalars.str(row, "label_default") == "true" } ?: return null
        val word = words[ref] ?: return null
        return if (JsonScalars.str(row, "display_name") == word) {
            ref to AccountKind(JsonScalars.str(row, "provider"), word)
        } else {
            null
        }
    }

    private fun madeUp(label: String): Boolean =
        label == PRIMARY_ID || hashTail.containsMatchIn(label) || ordinal.matches(label)
}
