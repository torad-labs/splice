// Account rows use a chosen label first, then the account email when its provider proves one, then splice's fallback.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import splice.core.auth.AccountRef
import splice.core.util.JsonScalars

// why: allow long provider email addresses while bounding the text rendered as an account name.
private const val MAX_ACCOUNT_EMAIL_CHARS = 254

// why: joins a provider and a shown email into one grouping key; no provider name or email holds a NUL.
private const val NAME_SEPARATOR = '\u0000'
private const val DISPLAY_NAME = "display_name"
private const val LABEL_DEFAULT = "label_default"
private const val ROW_REF = "ref"
private const val PROVIDER = "provider"

/** What a row is called, its [ref] when its credential proved one, and whether the name is splice's default rather
 *  than one a person gave (a default is what the console offers to name once). */
internal data class AccountName(val shown: String, val ref: String?, val isDefault: Boolean) {
    fun write(into: JsonObjectBuilder) {
        into.put(DISPLAY_NAME, shown)
        into.put(LABEL_DEFAULT, isDefault)
        into.put(ROW_REF, ref)
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

    // the plan word of each login named by its email, by ref, read only when another login proves the same email
    private val emailWords = HashMap<String, String>()
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
        return emailName(facts.email, ref)?.also { ref?.let { emailWords[it] = planWord(facts.plan) } }
            ?: fallback(facts.plan, ref)
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
        val word = planWord(plan)
        ref?.let { words[it] = word }
        return AccountName(word, ref, isDefault = true)
    }

    private fun planWord(plan: String?): String =
        plan?.takeIf(String::isNotBlank)?.replaceFirstChar(Char::uppercaseChar) ?: FALLBACK_WORD

    /** The stored number (Marlin via fin, Oct 12): an unnamed login reads its plan word and the number it keeps within
     *  its provider, "Max" then "Max 2", so no page needs its ref. Numbers are counted per provider, since every page
     *  puts the provider beside the name, and a login [rows] shows for the first time takes the next one. */
    fun settle(rows: List<JsonObject>): List<JsonObject> {
        val kinds = LinkedHashMap<String, AccountKind>()
        rows.forEach { row -> kind(row)?.let { kinds.putIfAbsent(it.first, it.second) } }
        val shown = store?.numbered(kinds) ?: AccountNumbers(emptyMap()).assign(kinds).mapValues { it.value.shown() }
        val settled = rows.map { row -> renamed(row, kind(row)?.let { shown[it.first] }) }
        return distinguishSharedEmails(settled)
    }

    /** Two logins can prove one email (two ChatGPT workspaces under one sign-in), and an email name alone would show
     *  both as the same row. Within a provider, each such login adds its plan word, then a number when the plan matches
     *  too, counted in ref order so a login keeps its name whatever order the roster lists it in. */
    private fun distinguishSharedEmails(rows: List<JsonObject>): List<JsonObject> {
        val refsByName = LinkedHashMap<String, MutableSet<String>>()
        rows.forEach { row ->
            emailKey(row)?.let { (name, ref) -> refsByName.getOrPut(name) { sortedSetOf() }.add(ref) }
        }
        val names = HashMap<String, String>()
        refsByName.filterValues { it.size > 1 }.forEach { (name, refs) ->
            nameShared(name.substringAfter(NAME_SEPARATOR), refs, names)
        }
        if (names.isEmpty()) return rows
        return rows.map { row -> renamed(row, emailKey(row)?.second?.let(names::get)) }
    }

    private fun nameShared(email: String, refs: Set<String>, into: MutableMap<String, String>) {
        val seen = HashMap<String, Int>()
        refs.forEach { ref ->
            val word = emailWords[ref] ?: FALLBACK_WORD
            val nth = seen.merge(word, 1, Int::plus) ?: 1
            into[ref] = if (nth == 1) "$email ($word)" else "$email ($word) $nth"
        }
    }

    private fun renamed(row: JsonObject, name: String?): JsonObject =
        if (name != null) JsonObject(row + (DISPLAY_NAME to JsonPrimitive(name))) else row

    /** An email-named login's provider and shown email as one key, with its ref, or null for any other row. */
    private fun emailKey(row: JsonObject): Pair<String, String>? {
        val ref = defaultRef(row)?.takeIf { it in emailWords } ?: return null
        val shown = JsonScalars.str(row, DISPLAY_NAME) ?: return null
        return (JsonScalars.str(row, PROVIDER).orEmpty() + NAME_SEPARATOR + shown) to ref
    }

    /** An unnamed login's ref and the provider and plan word it is counted among, or null for any other row. */
    private fun kind(row: JsonObject): Pair<String, AccountKind>? {
        val ref = defaultRef(row) ?: return null
        val word = words[ref] ?: return null
        return if (JsonScalars.str(row, DISPLAY_NAME) == word) {
            ref to AccountKind(JsonScalars.str(row, PROVIDER), word)
        } else {
            null
        }
    }

    /** A row's ref when its name is splice's default, or null when a person named it or it has no ref. */
    private fun defaultRef(row: JsonObject): String? =
        JsonScalars.str(row, ROW_REF)?.takeIf { JsonScalars.str(row, LABEL_DEFAULT) == "true" }

    private fun madeUp(label: String): Boolean =
        label == PRIMARY_ID || hashTail.containsMatchIn(label) || ordinal.matches(label)
}
