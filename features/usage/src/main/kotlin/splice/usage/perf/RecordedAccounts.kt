// NEW: labels only (Eli's ruling via splice-lead, 2026-10-12). A request row keeps the account value it was written
// with (an email, an account id, a pool label, a ref); this is how that value reads on the way out, so no page is
// handed an email or an id and the files are never rewritten.
package splice.usage.perf

/** One login as every page names it: its [ref] (what a page groups and filters by) and its [label] (all it shows). */
public data class NamedAccount(val ref: String, val label: String)

/** How one head's recorded account values read. Null leaves a value as recorded: a key's `VAR:fingerprint` names no
 *  person and no login, and a value the roster cannot place is the application's to name, not this route's. */
public fun interface HeadAccountNames {
    public fun name(recorded: String): NamedAccount?
}

/** Installed by the application, read once per head per request: the roster as it stands now. */
public fun interface RecordedAccounts {
    public suspend fun forHead(head: String): HeadAccountNames
}

/** A recorded value as a page sees it: the ref it groups under and the label it shows, or the value itself where
 *  [names] leaves it as recorded. */
internal class AccountReading(private val names: HeadAccountNames) {
    fun key(recorded: String?): String? = recorded?.let { names.name(it)?.ref ?: it }

    fun label(recorded: String?): String? = recorded?.let { names.name(it)?.label }

    /** A filter for [asked] matches a row recorded under that value, or under any value that reads as that ref, so a
     *  ref finds the rows written with an email before refs existed. */
    fun matches(recorded: String?, asked: String): Boolean = recorded == asked || key(recorded) == asked
}
