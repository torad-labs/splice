// NEW: labels only (Eli's ruling via splice-lead, 2026-10-12). Request rows keep the account value they were written
// with; this names each one by the Accounts roster as it stands, so Requests and Usage show the label Accounts shows
// and group by its ref, and a row written with an email before refs existed is found by that ref.
package splice.app.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.core.auth.AccountRef
import splice.core.util.JsonScalars
import splice.usage.perf.HeadAccountNames
import splice.usage.perf.NamedAccount
import splice.usage.perf.RecordedAccounts

// why: what a recorded login no roster row holds any more is called; it keeps its ref so its rows stay one group.
private const val SIGNED_OUT = "Signed-out account"
private const val SINGLE_LOGIN = "primary"
private const val NATIVE_PREFIX = "native:"

/** The Accounts roster as /api/accounts serves it: what every recorded login is named by. */
internal fun interface AccountRoster {
    suspend fun json(): String
}

internal class RosterAccountNames(
    /** The roster /api/accounts serves, read once per head per request. */
    private val roster: AccountRoster,
    private val logins: ClaudeLoginPlacesSource,
) : RecordedAccounts {
    private val json = Json { ignoreUnknownKeys = true }
    private val refShape = Regex("^[0-9a-f]{${AccountRef.CHARS}}$")
    private val uuidShape =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    override suspend fun forHead(head: String): HeadAccountNames {
        val byValue = HashMap<String, NamedAccount>()
        rows(head).forEach { row -> recordedAs(row, head).forEach { byValue.putIfAbsent(it, named(row)) } }
        val places = logins()
        return HeadAccountNames { recorded ->
            byValue[recorded]
                ?: places?.accountLabel(head, recorded)?.let(byValue::get)
                ?: signedOut(recorded)
        }
    }

    private suspend fun rows(head: String): List<JsonObject> {
        val body = JsonScalars.objectOrNull(json, roster.json())
        val all = body?.get("accounts") as? JsonArray ?: return emptyList()
        return all.mapNotNull { it as? JsonObject }.filter { row ->
            JsonScalars.str(row, "ref") != null &&
                (row["heads"] as? JsonArray).orEmpty().any { JsonScalars.str(it) == head }
        }
    }

    /** Every value a request row may have recorded this login under: its ref, its pool label on [head], the first
     *  login's `primary`, and a native place's word and selector. */
    private fun recordedAs(row: JsonObject, head: String): List<String> {
        val pool = JsonScalars.str(row["account_labels"] as? JsonObject, head)
        val single = SINGLE_LOGIN.takeIf { JsonScalars.str(row, "single_login") == "true" && pool == null }
        val place = JsonScalars.str(row["login_place"] as? JsonObject, "id")
        return listOfNotNull(JsonScalars.str(row, "ref"), pool, single, place, place?.let { NATIVE_PREFIX + it })
    }

    private fun named(row: JsonObject) =
        NamedAccount(JsonScalars.str(row, "ref").orEmpty(), JsonScalars.str(row, "display_name").orEmpty())

    /** A login the roster no longer holds still never shows its email or id: it reads as its ref. A key, or a label
     *  that names no person, stays as recorded. */
    private fun signedOut(recorded: String): NamedAccount? {
        val ref = when {
            refShape.matches(recorded) -> recorded
            '@' in recorded || uuidShape.matches(recorded) -> AccountRef.of(recorded)
            else -> return null
        }
        return NamedAccount(ref, "$SIGNED_OUT · $ref")
    }
}
