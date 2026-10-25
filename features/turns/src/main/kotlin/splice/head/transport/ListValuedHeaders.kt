// NEW: LAN share (docs/specs/lan-share.md). A turn's headers are the held login's own, then the caller's, and the
// caller's replace the login's name for name. That is right for a single value and wrong for a list: a login that
// needs a beta flag the caller did not name (a held Claude login's OAuth beta, on a node's turn) lost it to the
// caller's list. A list-valued header carries both lists, the caller's flags first, each flag once.
package splice.head.transport

internal object ListValuedHeaders {
    /** The headers whose value is a COMMA-SEPARATED LIST, so repeated field lines are one value split across lines,
     *  and two parties' values are joined rather than one replacing the other.
     *
     *  Only `anthropic-beta` qualifies. `Authorization` and `x-api-key` are singleton credentials and
     *  `anthropic-version` is a single token: joining a repeat of any of those would forge a value nobody sent, so
     *  for them the first line wins, which is what a singleton field means. */
    val names: Set<String> = setOf("anthropic-beta")

    // why: RFC 9110 5.3 joins repeated list-valued field lines with a comma and OWS; the vendor parses the beta flags
    // off exactly this separator, so it is the wire form, not a formatting choice.
    const val SEPARATOR: String = ", "

    /** [caller] over [account], as a map's plus puts it, except that a list-valued header both carry holds every flag
     *  of both: the caller's in its own order, then those of the account's it did not name. */
    fun over(account: Map<String, String>, caller: Map<String, String>): Map<String, String> {
        val joined = caller.mapValues { (name, value) ->
            if (name.lowercase() in names) {
                val accounts = account.filterKeys { it.equals(name, ignoreCase = true) }.values.flatMap(::flags)
                (flags(value) + accounts).distinct().joinToString(SEPARATOR)
            } else {
                value
            }
        }
        val listed = joined.keys.map(String::lowercase).filter { it in names }.toSet()
        return account.filterKeys { it.lowercase() !in listed } + joined
    }

    private fun flags(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)
}
