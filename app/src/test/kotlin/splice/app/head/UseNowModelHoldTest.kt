// NEW: Use now on a login held for one model's week (Marcos, Nov 3, 2026: "clicking 'use now' also didnt work"). That
// night accounts 2, 3 and 6 showed their week at 65 to 73% and a Use now button, Anthropic having refused Fable alone on
// them; splice held them for every model, so the pin he set fell through to the policy and nothing changed.
//
// The goal: Use now on a login that can serve the command's turns pins it, and the next turn of a model it serves takes
// it; the answer names each model the login cannot serve and until when, so the person sees why a turn of that model
// still goes elsewhere. Use now on a login the provider holds for every model pins nothing and says why, instead of
// pinning a promise nothing keeps. Driven through the switch route the console posts to, over the real pool and the
// projection the daemon builds for it.
package splice.app.head

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.pool.SwitchRoute
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.core.util.WallClock
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.RateLimitCooldown

private const val FABLE = "claude-fable-5-1"
private const val OPUS = "claude-opus-5-5"
private const val SESSION = "one-command"

class UseNowModelHoldTest {
    private val now = System.currentTimeMillis() / 1_000
    private val fableReset = now + 6 * 86_400
    private val weekReset = now + 2 * 86_400

    private val accounts = listOf("two", "four", "eight").map { label ->
        PoolAccount(
            label = label,
            primary = label == "eight",
            auth = Login(label),
            quota = AccountQuotaSource { null },
            cooldown = RateLimitCooldown(ElapsedClock { 0L }),
        )
    }.associateBy(PoolAccount::label)

    private val pool = AccountPool(accounts.values.toList(), WallClock { System.currentTimeMillis() })
        .also { it.order = listOf("two", "four", "eight") }

    init {
        // That night's holds, as the provider's 429s left them: two refused Fable for the week, four its whole week,
        // and eight, the login in use, its five hours.
        accounts.getValue(
            "two",
        ).cooldown.planHold.hold(PlanLimit("seven_day_overage_included", fableReset, model = FABLE)) {}
        accounts.getValue("four").cooldown.planHold.hold(PlanLimit("seven_day", weekReset)) {}
        accounts.getValue("eight").cooldown.planHold.hold(PlanLimit("five_hour", now + 7_200)) {}
    }

    private fun ApplicationTestBuilder.route() {
        val head = AccountHead(
            key = "claude-splice",
            auth = object : AuthProvider {
                override suspend fun credentials() = null
                override suspend fun describe() = AuthDescription(true, "claude-oauth", emptyMap())
            },
            restart = HeadRestart {},
            pool = requireNotNull(HeadAccountPools().source(pool)),
        )
        val switch = SwitchRoute(AccountHeadResolver { _, _ -> head })
        application { routing { post("/api/auth/{head}/switch") { switch.switchAccount(call) } } }
    }

    private suspend fun ApplicationTestBuilder.useNow(label: String): HttpResponse =
        client.post("/api/auth/claude-splice/switch") { setBody("""{"label":"$label"}""") }

    private fun chosen(model: String): String =
        when (val selection = pool.select(SESSION, emptySet(), null, model)) {
            is Selection.Chosen -> selection.account.account.label.also { selection.account.releaseCredentialProbe() }
            is Selection.Exhausted -> "none"
        }

    @Test
    fun `Use now on a login held only for Fable's week pins it, names the Fable hold, and Opus takes it`() =
        testApplication {
            route()

            val response = useNow("two")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status, body)
            val answer = Json.parseToJsonElement(body).jsonObject
            assertEquals("true", answer["ok"]?.jsonPrimitive?.content, body)
            val held = answer["models_held"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            assertEquals(listOf(FABLE), held.map { it["model"]?.jsonPrimitive?.content }, body)
            assertEquals(listOf(fableReset), held.map { it["until_epoch_seconds"]?.jsonPrimitive?.long }, body)
            assertEquals("two", pool.pinned())
            assertEquals("two", chosen(OPUS), "the pinned login serves the next Opus turn")
            assertNotEquals("two", chosen(FABLE), "a Fable turn goes where Fable is served")
        }

    @Test
    fun `Use now on a login the provider holds for every model pins nothing and says why`() = testApplication {
        route()

        val response = useNow("four")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Conflict, response.status, body)
        val error = Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("7-day plan limit reached" in error, error)
        assertTrue(LocalTimeText().at(weekReset) in error, "it says when the login serves again: $error")
        assertNull(pool.pinned(), "nothing is pinned")
    }

    // Review of #459, round 1: seven_day_oauth_apps is the week every Claude Code turn spends
    // (docs/specs/web-console.md, the client's rate_limits beside seven_day_opus and seven_day_sonnet), never one
    // model's, so an Opus turn refused on it holds the login for every model.
    @Test
    fun `Use now on a login whose OAuth-app week an Opus turn met pins nothing and says why`() = testApplication {
        route()
        val refused = PlanLimit("seven_day_oauth_apps", weekReset).metBy(OPUS)
        accounts.getValue("two").cooldown.planHold.hold(refused) {}

        val response = useNow("two")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Conflict, response.status, body)
        val error = Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content.orEmpty()
        assertTrue("plan limit reached" in error, error)
        assertTrue(LocalTimeText().at(weekReset) in error, "it says when the login serves again: $error")
        assertNull(pool.pinned(), "nothing is pinned")
    }
}

private class Login(private val token: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
}
