// Account naming contracts through /api/accounts: chosen names, proved-email defaults, then plan/ordinal fallbacks.
package splice.accounts.pool

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.AccountHead
import splice.accounts.signin.HeadRestart
import splice.core.auth.ACCOUNT_REF_FIELD
import splice.core.auth.AccountRef
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import java.nio.file.Path

class AccountLabelsTest {
    private val stored = AccountRef.of("acct-stored")

    private fun account(label: String, plan: String) = HeadAccountView(label, false, false, true, plan)

    private fun head(
        accounts: Map<String, Pair<String, String>>,
        key: String = "codex",
        emails: Map<String, String> = emptyMap(),
    ): AccountHead = AccountHead(
        key = key,
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
        },
        pool = HeadAccountPoolSource {
            HeadAccountPoolView(null, accounts.map { (label, plan) -> account(label, plan.first) }, null)
        },
        accountAuth = HeadAccountAuthSource {
            accounts.mapValues { (label, plan) ->
                AuthDescription(
                    true,
                    "chatgpt-oauth",
                    buildMap {
                        put("auth_path", "/synthetic/$label.json")
                        put(ACCOUNT_REF_FIELD, AccountRef.of(plan.second))
                        emails[label]?.let { put("email", it) }
                    },
                )
            }
        },
        restart = HeadRestart {},
    )

    @Test
    fun `an unnamed login reads its plan word, numbered where another shares it, offered naming`() = runBlocking {
        val pool = mapOf(
            "plus-$stored" to ("plus" to "acct-stored"),
            "codex-2" to ("pro" to "acct-ordinal"),
            "codex-3" to ("plus" to "acct-third"),
            "work" to ("plus" to "acct-named"),
        )
        val body = AccountsRoute(mapOf("codex" to head(pool))).accountsJson()
        val rows = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
            .associateBy { it.jsonObject.getValue("label").jsonPrimitive.content }
            .mapValues { (_, row) -> row.jsonObject }
        fun read(label: String, field: String) = rows.getValue(label).getValue(field).jsonPrimitive.content

        // two unnamed Plus logins are told apart by number, in roster order; a named one never takes a number
        assertEquals("Plus", read("plus-$stored", "display_name"))
        assertEquals("Plus 2", read("codex-3", "display_name"))
        assertEquals("true", read("plus-$stored", "label_default"))
        assertEquals(stored, read("plus-$stored", "ref"), "a stored default ends in the ref the account proves")
        assertEquals("Pro", read("codex-2", "display_name"), "a lone plan needs no ref")
        assertEquals("true", read("codex-2", "label_default"))
        assertEquals("work", read("work", "display_name"))
        assertEquals("false", read("work", "label_default"))
        assertFalse("acct-" in body, "no account id is anywhere in the roster")
    }

    @Test
    fun `unnamed logins of different providers never share a word, so each reads it alone`() = runBlocking {
        val heads = mapOf(
            "kimi" to head(mapOf("kimi-1" to ("" to "acct-kimi")), "kimi"),
            "muse" to head(mapOf("muse-1" to ("" to "acct-muse")), "muse"),
        )
        val body = AccountsRoute(heads).accountsJson(mapOf("kimi" to "kimi", "muse" to "muse"), emptyList(), emptyMap())
        val shown = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
            .map { it.jsonObject.getValue("display_name").jsonPrimitive.content }
        assertEquals(listOf("Account", "Account"), shown, "each is the only unnamed login on its provider")
    }

    @Test
    fun `known email is the untouched default and a chosen name takes priority`(@TempDir dir: Path) = runBlocking {
        val names = AccountNameStore(dir.resolve(ACCOUNT_NAMES_FILE))
        val emailRef = AccountRef.of("acct-long-email")
        val chosenRef = AccountRef.of("acct-chosen")
        val longEmail = "${"x".repeat(55)}@example.test"
        names.set(chosenRef, "Work account")
        val pool = mapOf(
            "codex-2" to ("plus" to "acct-long-email"),
            "codex-3" to ("plus" to "acct-chosen"),
            "codex-4" to ("plus" to "acct-second-email"),
        )
        val body = AccountsRoute(
            mapOf(
                "codex" to head(
                    pool,
                    emails = mapOf(
                        "codex-2" to longEmail,
                        "codex-3" to "work@example.test",
                        "codex-4" to "second@example.test",
                    ),
                ),
            ),
            names = names,
        ).accountsJson()
        val rows = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
            .associateBy { it.jsonObject.getValue("label").jsonPrimitive.content }
            .mapValues { (_, row) -> row.jsonObject }
        fun read(label: String, field: String) = rows.getValue(label).getValue(field).jsonPrimitive.content

        assertEquals(longEmail, read("codex-2", "display_name"), "a normal long email remains the account's default")
        assertEquals("true", read("codex-2", "label_default"))
        assertEquals(emailRef, read("codex-2", "ref"))
        assertEquals("Work account", read("codex-3", "display_name"), "the chosen label beats the proved email")
        assertEquals("false", read("codex-3", "label_default"))
        assertEquals("second@example.test", read("codex-4", "display_name"), "settling does not number email defaults")
        assertEquals("true", read("codex-4", "label_default"))
        assertEquals(mapOf(chosenRef to "Work account"), names.names(), "email defaults never become chosen names")
    }

    @Test
    fun `an email default is not renumbered when another row for its ref needs a plan fallback`() = runBlocking {
        val sharedRef = "acct-shared"
        val pool = mapOf(
            "codex-2" to ("plus" to sharedRef),
            "codex-3" to ("plus" to sharedRef),
        )
        val body = AccountsRoute(
            mapOf("codex" to head(pool, emails = mapOf("codex-3" to "shared@example.test"))),
        ).accountsJson()
        val rows = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray
            .associateBy { it.jsonObject.getValue("label").jsonPrimitive.content }
        fun read(label: String, field: String) = rows.getValue(label).jsonObject.getValue(field).jsonPrimitive.content

        assertEquals("Plus", read("codex-2", "display_name"))
        assertEquals("shared@example.test", read("codex-3", "display_name"))
        assertEquals("true", read("codex-3", "label_default"))
    }

    @Test
    fun `a number is kept for good, a signed-out login keeps its own, and a new login never takes it`(
        @TempDir dir: Path,
    ) = runBlocking {
        val names = AccountNameStore(dir.resolve(ACCOUNT_NAMES_FILE))
        suspend fun shown(pool: Map<String, Pair<String, String>>) =
            Json.parseToJsonElement(AccountsRoute(mapOf("codex" to head(pool)), names = names).accountsJson())
                .jsonObject.getValue("accounts").jsonArray
                .associate {
                    it.jsonObject.getValue(
                        "label",
                    ).jsonPrimitive.content to it.jsonObject.getValue("display_name").jsonPrimitive.content
                }

        val first = "codex-2" to ("plus" to "acct-first")
        val second = "codex-3" to ("plus" to "acct-second")
        assertEquals(mapOf("codex-2" to "Plus", "codex-3" to "Plus 2"), shown(mapOf(first, second)))
        // the first signs out: the second keeps "Plus 2" rather than moving up, and Requests reads the first as "Plus"
        assertEquals(mapOf("codex-3" to "Plus 2"), shown(mapOf(second)))
        val gone = AccountRef.of("acct-first")
        assertEquals("Plus", names.numbered(mapOf(gone to AccountKind(null, SIGNED_OUT_WORD))).getValue(gone))
        // a login seen for the first time takes the next number, never a freed one
        assertEquals(
            mapOf("codex-3" to "Plus 2", "codex-4" to "Plus 3"),
            shown(mapOf(second, "codex-4" to ("plus" to "acct-new"))),
        )
        // one never seen signed in is numbered among the signed-out, and gives that up once it is back
        val stranger = AccountRef.of("acct-stranger")
        assertEquals(
            "Signed-out account",
            names.numbered(mapOf(stranger to AccountKind(null, SIGNED_OUT_WORD))).getValue(stranger),
        )
        assertEquals("Plus 4", shown(mapOf("codex-5" to ("plus" to "acct-stranger"))).getValue("codex-5"))
    }

    @Test
    fun `chosen labels and custom email names win, and blank restores the default`(
        @TempDir dir: Path,
    ) = testApplication {
        val names = AccountNameStore(dir.resolve(ACCOUNT_NAMES_FILE))
        val pool = mapOf("work" to ("plus" to "acct-named"), "codex-2" to ("pro" to "acct-ordinal"))
        val roster = AccountsRoute(mapOf("codex" to head(pool)), names = names)
        routing {
            put("/api/accounts/names/{ref}") { AccountNameRoute(names).set(call) }
            get("/api/accounts") { call.respondText(roster.accountsJson()) }
        }
        suspend fun shown(label: String): Pair<String, String> {
            val rows = Json.parseToJsonElement(client.get("/api/accounts").bodyAsText()).jsonObject
                .getValue("accounts").jsonArray.map { it.jsonObject }
            val row = rows.single { it.getValue("label").jsonPrimitive.content == label }
            return row.getValue(
                "display_name",
            ).jsonPrimitive.content to row.getValue("label_default").jsonPrimitive.content
        }
        val ordinal = AccountRef.of("acct-ordinal")
        val named = AccountRef.of("acct-named")

        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/accounts/names/$ordinal") {
                setBody("""{"name":"Side gig"}""")
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/accounts/names/$named") { setBody("""{"name":"Day job"}""") }.status,
        )
        assertEquals("Side gig" to "false", shown("codex-2"))
        assertEquals("Day job" to "false", shown("work"), "a chosen name outranks the label the login was stored under")

        val email = client.put("/api/accounts/names/$ordinal") { setBody("""{"name":"me@example.com"}""") }
        assertEquals(HttpStatusCode.OK, email.status)
        assertEquals("me@example.com" to "false", shown("codex-2"), "an explicitly chosen email is a custom name")

        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/accounts/names/$ordinal") { setBody("""{"name":" "}""") }.status,
        )
        assertEquals("Pro" to "true", shown("codex-2"))
    }
}
