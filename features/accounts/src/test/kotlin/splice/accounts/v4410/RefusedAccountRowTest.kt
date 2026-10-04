// NEW: V4-410 — a pool credential splice refuses (a symlinked <label>.json, V4-405) reaches /api/accounts as
// refused, in words, in its own field. Before this the files layer knew and nothing past it did, so the row
// read credential_present false like an orphan and Needs you offered "Sign in again" for a label the writer
// refuses. An orphan stays a plain missing credential: no refusal, so the console still offers the renewal.
package splice.accounts.v4410

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.pool.AccountsRoute
import splice.accounts.pool.HeadAccountAuthSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider

class RefusedAccountRowTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sentence = "'linked' is a symbolic link, and splice does not load a linked credential; " +
        "remove the link and sign in again, or sign in under a different label"

    @Test
    fun `a refused account carries its refusal in words and no credential`() = runBlocking {
        val rows = rows(
            account("primary", credentialPresent = true) to null,
            account("linked", credentialPresent = false) to sentence,
        )

        val linked = rows.getValue("linked")
        assertEquals(sentence, linked["refusal"]?.jsonPrimitive?.content)
        assertEquals("false", linked["credential_present"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an orphan is a missing credential with no refusal so it still renews`() = runBlocking {
        val rows = rows(account("primary", true) to null, account("work", credentialPresent = false) to null)

        val work = rows.getValue("work")
        assertEquals("false", work["credential_present"]?.jsonPrimitive?.content)
        assertTrue(work.containsKey("refusal"), "the field is always on the row")
        assertEquals(JsonNull, work["refusal"])
    }

    @Test
    fun `an account splice can load has no refusal`() = runBlocking {
        val rows = rows(account("primary", true) to null, account("plus-a", true) to null)

        assertEquals(listOf<JsonElement>(JsonNull, JsonNull), rows.values.map { it["refusal"] })
    }

    /** Each account with the refusal its own description carries, if any. */
    private suspend fun rows(vararg accounts: Pair<HeadAccountView, String?>): Map<String, JsonObject> {
        val views = accounts.map { it.first }
        val pool = HeadAccountPoolView(selectedLabel = "primary", accounts = views, lastSwitch = null)
        val head = AccountHead(
            key = "claudex",
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
            },
            restart = HeadRestart {},
            pool = HeadAccountPoolSource { pool },
            accountAuth = HeadAccountAuthSource {
                accounts.associate { (view, refusal) -> view.label to described(view.label, refusal) }
            },
        )
        val body = json.parseToJsonElement(AccountsRoute(mapOf(head.key to head)).accountsJson(NOW)).jsonObject
        return body["accounts"]!!.jsonArray.map { it.jsonObject }
            .associateBy { it["label"]!!.jsonPrimitive.content }
    }

    private fun described(label: String, refusal: String?) = AuthDescription(
        present = refusal == null,
        kind = "chatgpt-oauth",
        fields = mapOf("auth_path" to "/pool/$label.json") + listOfNotNull(refusal?.let { "refusal" to it }),
    )

    private fun account(label: String, credentialPresent: Boolean) = HeadAccountView(
        label = label,
        primary = label == "primary",
        selected = label == "primary",
        available = credentialPresent,
        plan = null,
        fiveHourUsedPercent = null,
        fiveHourResetEpochSeconds = null,
        sevenDayUsedPercent = null,
        sevenDayResetEpochSeconds = null,
        credentialPresent = credentialPresent,
    )

    private companion object {
        const val NOW = 1_790_630_000L
    }
}
