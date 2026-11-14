// NEW: a Claude login's limit resets, listed and spent through the routes the console and `splice limit-reset` call
// (Nov 13, 2026: every Fable login on one machine ran out at about 6:24 PM CT and no reset could be called from splice).
// Anthropic is a stand-in here: no test reaches its real reset address.
//
// The goal: a person sees how many of each kind a login has left and whether one can be spent now; a spend asked for
// spends that kind once, on that login, and the login is read again so the turns held for it go on; a spend that
// cannot help, or names nothing, never reaches Anthropic's spend address; and a spend whose answer was lost is asked
// again as the same request, so it is never spent twice.
package splice.app.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.order.AccountOrderStore
import splice.accounts.pool.LimitResetRoutes
import splice.accounts.signin.HeadRestart
import splice.app.auth.claude.AuthClient
import splice.app.auth.claude.ClaudeLimitResets
import splice.app.provider.ClaudeLoginHeaders
import splice.app.provider.WiredAccount
import splice.app.provider.WiredAccountQuota
import splice.app.provider.WiredAccountsRead
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import java.io.IOException
import java.nio.file.Path

private const val HEAD = "claude-splice"
private const val LOGIN = "account-4"
private const val ORG = "org-synthetic"
private const val SPEND_PATH = "/api/organizations/$ORG/reset_rate_limits"

// A login with two Fable resets of three that can be spent now, and a weekly reset Anthropic keeps until a limit is hit.
private const val RESETS_REPLY = """{"five_hour":{"utilization":12.0},"cedar_ember":{"eligible":true,"grants":[
{"id":"fable_grant","label":"Fable","resets_total":3,"resets_left":2,"clears":["seven_day_omelette","five_hour"],
"paused":false,"usable_now":true,"gives_back_weekly":true,"ends_at":"2099-01-01T00:00:00Z"},
{"id":"weekly_grant","label":"Weekly","resets_total":1,"resets_left":1,"clears":["seven_day"],"paused":false,
"usable_now":false,"type":"weekly","gives_back_weekly":false,"unavailable_reason":"needs_limit"}]}}"""

class LimitResetRoutesTest {
    @TempDir
    lateinit var dir: Path

    /** What Anthropic's stand-in answers a spend with: a body, or null to lose the answer on the way back. */
    private var spendAnswer: String? = """{"result":"reset","resets_left":1,"cleared":["seven_day_omelette"]}"""
    private val spends = mutableListOf<JsonObject>()

    private suspend fun MockRequestHandleScope.anthropic(request: HttpRequestData): HttpResponseData {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
        return when {
            request.url.encodedPath == "/api/oauth/usage" -> {
                assertEquals("1", request.url.parameters["cedar_ember"])
                respond(RESETS_REPLY, HttpStatusCode.OK, json)
            }
            request.url.encodedPath == "/api/oauth/profile" ->
                respond("""{"account":{"uuid":"a"},"organization":{"uuid":"$ORG"}}""", HttpStatusCode.OK, json)
            request.method == HttpMethod.Post && request.url.encodedPath == SPEND_PATH -> {
                spends += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                respond(spendAnswer ?: throw IOException("the answer was lost"), HttpStatusCode.OK, json)
            }
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun ApplicationTestBuilder.routes(claudeLogins: Boolean = true) {
        val client = ClaudeLimitResets(
            { "claude-cli/2.1.325" },
            {},
            AuthClient { HttpClient(MockEngine { anthropic(it) }) { install(HttpTimeout) } },
        )
        val logins = if (claudeLogins) listOf(wired(LOGIN)) else emptyList()
        val resets = HeadLimitResets(
            HEAD,
            WiredAccountsRead { logins },
            object : LoginReadAgain {
                override suspend fun readAgain(label: String): Boolean = label == LOGIN
            },
            client,
            {},
        )
        val pool = AccountPool(
            listOf(
                PoolAccount(
                    label = LOGIN,
                    primary = true,
                    auth = ResetLogin(),
                    quota = AccountQuotaSource { null },
                    cooldown = RateLimitCooldown(ElapsedClock { 0L }),
                ),
            ),
            WallClock { System.currentTimeMillis() },
        )
        val head = AccountHead(
            key = HEAD,
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() = AuthDescription(true, "claude-oauth", emptyMap())
            },
            restart = HeadRestart {},
            pool = HeadAccountPools().source(pool, HEAD, AccountOrderStore(dir.resolve("order.json")), resets = resets),
        )
        val routes = LimitResetRoutes(AccountHeadResolver { _, _ -> head })
        application {
            routing {
                get("/api/auth/{head}/resets") { routes.list(call) }
                post("/api/auth/{head}/reset") { routes.spend(call) }
            }
        }
    }

    private fun wired(label: String) = WiredAccount(
        label = label,
        primary = true,
        auth = ResetLogin(),
        quota = WiredAccountQuota(dir.resolve("$label-quota.json")),
        extraHeaders = ClaudeLoginHeaders,
    )

    private suspend fun ApplicationTestBuilder.spend(body: String): HttpResponse =
        client.post("/api/auth/$HEAD/reset") { setBody(body) }

    private fun parsed(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun text(obj: JsonObject?, name: String): String? = obj?.get(name)?.jsonPrimitive?.content

    @Test
    fun `the list says how many of each kind a login has left and whether one can be spent now`() = testApplication {
        routes()

        val response = client.get("/api/auth/$HEAD/resets")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        val login = parsed(body)["logins"]?.jsonArray?.single()?.jsonObject
        assertEquals(LOGIN, text(login, "label"), body)
        val kinds = login?.get("kinds")?.jsonArray?.map { it.jsonObject }.orEmpty().associateBy { text(it, "kind") }
        val fable = kinds["fable"]
        assertEquals("Fable reset", text(fable, "name"), body)
        assertEquals("2", text(fable, "left"), body)
        assertEquals("3", text(fable, "total"), body)
        assertEquals("true", text(fable, "usable_now"), body)
        val weekly = kinds["weekly"]
        assertEquals("1", text(weekly, "left"), body)
        assertEquals("false", text(weekly, "usable_now"), body)
        assertEquals(
            "A weekly reset can be used only once this login reaches a limit it clears.",
            text(weekly, "not_now"),
            body,
        )
        assertEquals(emptyList<JsonObject>(), spends, "listing spends nothing")
    }

    @Test
    fun `a Fable reset asked for is spent once on that login, and the login is read again`() = testApplication {
        routes()

        val response = spend("""{"label":"$LOGIN","kind":"fable"}""")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status, body)
        val answer = parsed(body)
        assertEquals("true", text(answer, "ok"), body)
        assertEquals("Spent a Fable reset. 1 left.", text(answer, "said"), body)
        assertEquals("true", text(answer, "read_again"), body)
        assertEquals(listOf("seven_day_omelette"), answer["cleared"]?.jsonArray?.map { it.jsonPrimitive.content })
        val sent = spends.single()
        assertEquals("cedar_ember", text(sent, "program"))
        assertEquals("fable_grant", text(sent, "grant_id"))
    }

    @Test
    fun `a reset that cannot help now, or a body that names nothing, never reaches the spend address`() =
        testApplication {
            routes()

            val weekly = spend("""{"label":"$LOGIN","kind":"weekly"}""")
            assertEquals(HttpStatusCode.Conflict, weekly.status)
            assertEquals(
                "A weekly reset can be used only once this login reaches a limit it clears.",
                text(parsed(weekly.bodyAsText()), "said"),
            )
            assertEquals(HttpStatusCode.BadRequest, spend("""{"label":"$LOGIN","kind":"monthly"}""").status)
            assertEquals(HttpStatusCode.BadRequest, spend("""{"label":"$LOGIN"}""").status)
            assertEquals(HttpStatusCode.NotFound, spend("""{"label":"account-9","kind":"fable"}""").status)
            assertEquals(emptyList<JsonObject>(), spends)
        }

    @Test
    fun `a spend whose answer was lost is said as unconfirmed, and asking again sends the same request`() =
        testApplication {
            routes()
            spendAnswer = null

            val lost = spend("""{"label":"$LOGIN","kind":"fable"}""")
            val lostBody = parsed(lost.bodyAsText())
            assertEquals(HttpStatusCode.Conflict, lost.status)
            assertEquals("unconfirmed", text(lostBody, "outcome"))
            assertTrue(text(lostBody, "said").orEmpty().contains("never spent twice"), lostBody.toString())

            spendAnswer = """{"result":"reset","resets_left":1}"""
            assertEquals(HttpStatusCode.OK, spend("""{"label":"$LOGIN","kind":"fable"}""").status)
            assertEquals(2, spends.size)
            assertEquals(text(spends[0], "request_id"), text(spends[1], "request_id"))
        }

    @Test
    fun `a command with no Claude login says it has no resets`() = testApplication {
        routes(claudeLogins = false)

        val list = client.get("/api/auth/$HEAD/resets")
        assertEquals(HttpStatusCode.BadRequest, list.status)
        assertEquals("head '$HEAD' has no logins with limit resets", text(parsed(list.bodyAsText()), "error"))
        assertEquals(HttpStatusCode.BadRequest, spend("""{"label":"$LOGIN","kind":"fable"}""").status)
    }
}

private class ResetLogin : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token")

    override suspend fun refresh(): Credentials = credentials()

    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
}
