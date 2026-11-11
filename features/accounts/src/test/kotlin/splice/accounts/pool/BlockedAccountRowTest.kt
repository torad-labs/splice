// A login its provider blocks until its owner acts reaches /api/accounts with the step in words, in its own field, and
// with no refusal. The console reads a refusal as signed out and offers Sign in, which lifts no block: on Nov 11, 2026
// the card of a Claude login whose organization had turned subscription access off read "Signed out" beside a Sign in
// button, because the native row said "This login was refused; run claude to sign in again." for any held login.
package splice.accounts.pool

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginCredential
import splice.accounts.claude.ClaudeLoginIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginStanding
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.failure.AccountBlock
import splice.core.failure.OwnerStep

class BlockedAccountRowTest {
    private val organization = AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, "the organization's settings")
    private val terms = AccountBlock(OwnerStep.ACCEPT_TERMS, "claude.ai")
    private val organizationWords = "Blocked until its organization allows subscription access"

    @Test
    fun `a pool login its organization blocks says so in owner_block and carries no refusal`() = runBlocking {
        val rows = poolRows(account("primary", HeadAccountCredential()), account("work", held(organization)))

        val work = rows.getValue("work")
        assertEquals(organizationWords, work["owner_block"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, work["refusal"])
        assertEquals(JsonNull, rows.getValue("primary")["owner_block"], "a login nothing blocks carries no step")
    }

    @Test
    fun `a pool login whose owner has terms to accept names where they are accepted`() = runBlocking {
        val rows = poolRows(account("primary", HeadAccountCredential()), account("work", held(terms)))

        assertEquals(
            "Blocked until its owner accepts the provider's updated terms in claude.ai",
            rows.getValue("work")["owner_block"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the native Claude login its organization blocks is held, never told to sign in again`() = runBlocking {
        val row = nativeRow(held(organization))

        assertEquals(organizationWords, row["owner_block"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, row["refusal"])
        assertEquals("false", row["available"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the native Claude login held for a refused token still says to sign in again`() = runBlocking {
        val row = nativeRow(held(null))

        assertEquals(JsonNull, row["owner_block"])
        assertEquals("This login was refused; run claude to sign in again.", row["refusal"]?.jsonPrimitive?.content)
    }

    private suspend fun poolRows(vararg accounts: HeadAccountView): Map<String, JsonObject> {
        val head = AccountHead(
            key = "claudex",
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
            },
            restart = HeadRestart {},
            pool = HeadAccountPoolSource { HeadAccountPoolView("primary", accounts.toList(), null) },
            accountAuth = HeadAccountAuthSource {
                accounts.associate {
                    it.label to AuthDescription(true, "chatgpt-oauth", mapOf("auth_path" to "/pool/${it.label}.json"))
                }
            },
        )
        val body = Json.parseToJsonElement(AccountsRoute(mapOf(head.key to head)).accountsJson(NOW)).jsonObject
        return body.getValue("accounts").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("label").jsonPrimitive.content }
    }

    /** The row of Claude Code's own login on a head whose pool holds it with [credential]. */
    private suspend fun nativeRow(credential: HeadAccountCredential): JsonObject {
        val native = ClaudeLoginPlaceView(
            ClaudeLoginPlaceId.NATIVE,
            "claude-splice",
            ClaudeLoginCredential("/synthetic/native/.credentials.json", true),
            ClaudeLoginIdentity(ClaudeAccountIdentity("native-account", "native@synthetic.test")),
            null,
            ClaudeLoginStanding(null, null),
        )
        val held = account("native:${ClaudeLoginPlaceId.NATIVE.wire}", credential)
        val head = AccountHead(
            key = "claude-splice",
            auth = object : AuthProvider {
                override suspend fun credentials(): Credentials = Credentials.ClientForwarded
                override suspend fun describe() = AuthDescription(true, "client")
            },
            pool = HeadAccountPoolSource { HeadAccountPoolView(null, listOf(held), null) },
            accountAuth = HeadAccountAuthSource { emptyMap() },
            restart = HeadRestart { },
        )
        val body = AccountsRoute(mapOf(head.key to head)).accountsJson(
            mapOf(head.key to "anthropic"),
            listOf(native),
            mapOf(head.key to null),
            nowSeconds = NOW,
        )
        return Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
            .map { it.jsonObject }.single { it["label"]?.jsonPrimitive?.content == ClaudeLoginPlaceId.NATIVE.wire }
    }

    /** A credential the pool holds out of selection: by [block] when its owner must act, else by a refused token. */
    private fun held(block: AccountBlock?) = HeadAccountCredential(
        present = true,
        excludedUntilEpochMillis = NOW * 1_000 + 300_000,
        exclusionReason = "terminal_401",
        ownerBlock = block,
    )

    private fun account(label: String, credential: HeadAccountCredential) = HeadAccountView(
        label = label,
        primary = label == "primary",
        selected = label == "primary",
        available = credential.excludedUntilEpochMillis == null,
        plan = null,
        credential = credential,
    )

    private companion object {
        const val NOW = 1_790_630_000L
    }
}
