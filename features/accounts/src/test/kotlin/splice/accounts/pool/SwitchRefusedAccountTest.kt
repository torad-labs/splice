// The daemon refuses a switch to an account it cannot serve, and says why. Marlin's walk
// (ef86845f3): POST /api/auth/codex/switch {label: linked} answered ok:true and pinned a symlinked credential the
// writer refuses, and the console said "Takes effect on the next turn"; an account with no credential file was
// pinned the same way. A refusal is a 409 with the account's own sentence and pins nothing; a label the pool does
// not have keeps its 400; an account that serves, even one cooling down, still switches. Driven through the
// route itself, over a pool double that records every pin.
package splice.accounts.pool

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.AccountHead
import splice.accounts.AccountHeadResolver
import splice.accounts.signin.HeadRestart
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider

private const val REFUSED = "'linked' is a symbolic link, and splice does not load a linked credential; " +
    "remove the link and sign in again, or sign in under a different label"

/** The pool the route discovers by checked cast: its view and its pin on one object, recording every pin. */
private class RecordingPool(private val accounts: List<HeadAccountView>) : HeadAccountPoolSource, HeadAccountPinSource {
    val pins = mutableListOf<String>()

    override fun view(sessionId: String?) =
        HeadAccountPoolView(selectedLabel = "primary", accounts = accounts, lastSwitch = null)

    override fun pin(label: String, sessionId: String?): Boolean =
        (label in accounts.map { it.label }).also { if (it) pins += label }

    override fun unpin(sessionId: String?) = Unit
}

class SwitchRefusedAccountTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun account(
        label: String,
        credentialPresent: Boolean = true,
        excludedUntil: Long? = null,
    ) = HeadAccountView(
        label = label,
        primary = label == "primary",
        selected = label == "primary",
        available = credentialPresent && excludedUntil == null,
        plan = null,
        credential = HeadAccountCredential(present = credentialPresent, excludedUntilEpochMillis = excludedUntil),
    )

    private val pool = RecordingPool(
        listOf(
            account("primary"),
            account("linked", credentialPresent = false),
            account("gone", credentialPresent = false),
            account("cooling", excludedUntil = 4_000_000_000_000L),
        ),
    )

    private fun head(withDescriptions: Boolean) = AccountHead(
        key = "claudex",
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
        },
        restart = HeadRestart {},
        pool = pool,
        accountAuth = if (withDescriptions) {
            HeadAccountAuthSource {
                mapOf(
                    "linked" to AuthDescription(false, "chatgpt-oauth", mapOf("refusal" to REFUSED)),
                    "gone" to AuthDescription(false, "chatgpt-oauth", mapOf("auth_path" to "/pool/gone.json")),
                )
            }
        } else {
            null
        },
    )

    private fun ApplicationTestBuilder.route(withDescriptions: Boolean = true) {
        val switch = SwitchRoute(AccountHeadResolver { _, _ -> head(withDescriptions) })
        application { routing { post("/api/auth/{head}/switch") { switch.switchAccount(call) } } }
    }

    private suspend fun ApplicationTestBuilder.switchTo(label: String): HttpResponse =
        client.post("/api/auth/claudex/switch") { setBody("""{"label":"$label"}""") }

    private fun field(body: String, name: String) =
        json.parseToJsonElement(body).jsonObject[name]?.jsonPrimitive?.content

    @Test
    fun `a refused link is a 409 with the refusal sentence and pins nothing`() = testApplication {
        route()

        val response = switchTo("linked")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertEquals("false", field(body, "ok"), body)
        assertEquals(REFUSED, field(body, "error"), body)
        assertEquals(emptyList<String>(), pool.pins)
    }

    @Test
    fun `an account with no credential file is a 409 that names it and pins nothing`() = testApplication {
        route()

        val response = switchTo("gone")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Conflict, response.status, body)
        assertEquals("'gone' has no credential file; sign in to it first", field(body, "error"), body)
        assertEquals(emptyList<String>(), pool.pins)
    }

    @Test
    fun `a head that describes no accounts still refuses a missing credential in words`() = testApplication {
        route(withDescriptions = false)

        val response = switchTo("linked")

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertTrue("has no credential file" in field(response.bodyAsText(), "error").orEmpty(), response.bodyAsText())
        assertEquals(emptyList<String>(), pool.pins)
    }

    @Test
    fun `an account that serves still switches, one cooling down included`() = testApplication {
        route()

        val first = switchTo("primary")
        val second = switchTo("cooling")

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        assertEquals("true", field(second.bodyAsText(), "ok"))
        assertEquals(listOf("primary", "cooling"), pool.pins)
    }

    @Test
    fun `a label the pool does not have keeps its 400`() = testApplication {
        route()

        val response = switchTo("no-such-label")

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue("no-such-label" in response.bodyAsText(), response.bodyAsText())
        assertEquals(emptyList<String>(), pool.pins)
    }
}
