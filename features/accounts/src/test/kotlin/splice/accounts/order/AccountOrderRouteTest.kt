// NEW: order endpoints validate identity and update the capability without restarting a head.
package splice.accounts.order

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginCredential
import splice.accounts.claude.ClaudeLoginIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginStanding
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider

class AccountOrderRouteTest {
    @Test
    fun `routes round trip valid policy and reject malformed duplicate and unknown identities`() = testApplication {
        val source = Source()
        val head = head(source)
        val route = AccountOrderRoute(AccountHeadResolver { _, _ -> head })
        application {
            routing {
                get("/api/auth/{head}/order") { route.get(call) }
                put("/api/auth/{head}/order") { route.set(call) }
            }
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/head/order").status)
        val changed = client.put("/api/auth/head/order") { setBody("""{"order":["backup","primary"]}""") }
        assertEquals(HttpStatusCode.OK, changed.status)
        val payload = Json.parseToJsonElement(changed.bodyAsText()).jsonObject
        assertEquals("head", payload.getValue("head").jsonPrimitive.content)
        assertEquals(
            listOf("backup", "primary"),
            payload.getValue("effective_order").jsonArray.map { it.jsonPrimitive.content },
        )
        for (body in listOf(
            "{}",
            """{"order":[null]}""",
            """{"order":["other"]}""",
            """{"order":["primary","primary"]}""",
            // A body that is not a JSON object at all. These answered 404 Not Found on a head that
            // exists and is selectable, because the route returned without writing its refusal.
            "not json at all",
            "[1,2,3]",
        )) {
            val refused = client.put("/api/auth/head/order") { setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, refused.status, body)
            assertEquals(listOf("backup", "primary"), source.order())
        }
        val cleared = client.put("/api/auth/head/order") { setBody("""{"order":[]}""") }
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertEquals(listOf("primary", "backup"), source.effectiveOrder())
    }

    @Test
    fun `a single login is visible but has no selectable order identity`() = testApplication {
        val route = AccountOrderRoute(AccountHeadResolver { _, _ -> head(null) })
        application { routing { get("/api/auth/{head}/order") { route.get(call) } } }
        assertEquals(HttpStatusCode.Conflict, client.get("/api/auth/head/order").status)
    }

    @Test
    fun `one proven account in both native places has an honest read but no mutable order`() = testApplication {
        var native = ClaudeLoginPlaceId.entries.map { place ->
            ClaudeLoginPlaceView(
                id = place,
                head = "head",
                credential = ClaudeLoginCredential("synthetic/${place.wire}", true),
                identity = ClaudeLoginIdentity(ClaudeAccountIdentity("synthetic-account", "synthetic@example.invalid")),
                quota = null,
                standing = ClaudeLoginStanding(null, null),
            )
        }
        val route = AccountOrderRoute(AccountHeadResolver { _, _ -> head(null) })
        application {
            routing {
                get("/api/auth/{head}/order") { route.get(call, native) }
                put("/api/auth/{head}/order") { route.set(call) }
            }
        }
        val answer = client.get("/api/auth/head/order")
        assertEquals(HttpStatusCode.OK, answer.status)
        val payload = Json.parseToJsonElement(answer.bodyAsText()).jsonObject
        assertEquals("true", payload.getValue("single_account").jsonPrimitive.content)
        assertEquals(
            emptyList<String>(),
            payload.getValue("effective_order").jsonArray.map { it.jsonPrimitive.content },
        )
        val update = client.put("/api/auth/head/order") { setBody("""{"order":[]}""") }
        assertEquals(HttpStatusCode.Conflict, update.status)
        for (identity in listOf(null, ClaudeAccountIdentity("synthetic-other", "synthetic@example.invalid"))) {
            native = listOf(native.first(), native.last().copy(identity = ClaudeLoginIdentity(identity)))
            assertEquals(HttpStatusCode.Conflict, client.get("/api/auth/head/order").status)
        }
    }

    private fun head(pool: HeadAccountPoolSource?) = AccountHead(
        key = "head",
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe(): AuthDescription = AuthDescription(false, "test")
        },
        pool = pool,
        restart = HeadRestart { error("an order update must not restart") },
    )

    private class Source : HeadAccountPoolSource, HeadAccountOrderSource {
        private var labels = emptyList<String>()
        override fun view(sessionId: String?) = HeadAccountPoolView(null, emptyList(), null)
        override fun order(): List<String> = labels
        override fun effectiveOrder(): List<String> = (labels + listOf("primary", "backup")).distinct()
        override fun nextTarget(): String = effectiveOrder()[0]
        override fun followingTarget(): String = effectiveOrder()[1]
        override fun setOrder(labels: List<String>): Boolean {
            if (labels.distinct().size != labels.size || labels.any { it !in listOf("primary", "backup") }) return false
            this.labels = labels
            return true
        }
    }
}
