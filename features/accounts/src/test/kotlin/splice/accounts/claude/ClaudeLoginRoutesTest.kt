// NEW: management addresses the requested command and rejects malformed requests before authentication starts.
package splice.accounts.claude

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.accounts.signin.LoginPrompt
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus

private class TestNativePlaces : ClaudeLoginPlaces {
    val requested = mutableListOf<Pair<ClaudeLoginPlaceId, String?>>()
    val status = LoginStatus(
        "native-id",
        "claude-splice",
        LoginState.WAITING,
        prompt = LoginPrompt(browserUrl = "https://claude.ai/fixture"),
    )

    override fun places(): List<ClaudeLoginPlaceView> = ClaudeLoginPlaceId.entries.map {
        ClaudeLoginPlaceView(
            it,
            "claude-splice",
            ClaudeLoginCredential("/synthetic/${it.wire}", false),
            ClaudeLoginIdentity(null),
            null,
            ClaudeLoginStanding(null, null),
        )
    }

    override suspend fun login(place: ClaudeLoginPlaceId, label: String?): LoginStatus {
        requested += place to label
        return status
    }

    override suspend fun refresh(place: ClaudeLoginPlaceId): ClaudeLoginPlaceView = places().single { it.id == place }
    override fun poll(id: String): LoginStatus? = status.takeIf { it.id == id }
    override suspend fun submit(id: String, code: String): Boolean = id == status.id && code == "synthetic-code"
    override fun carrying(head: String): ClaudeLoginPlaceId? =
        ClaudeLoginPlaceId.SPLICE.takeIf { head == "claude-splice" }
}

class ClaudeLoginRoutesTest {
    @Test
    fun `native routes target the command, reuse head polling, and refuse malformed labels before login`() =
        testApplication {
            val source = TestNativePlaces()
            val route = ClaudeLoginRoutes(ClaudeLoginPlacesSource { source })
            application {
                routing {
                    post("/api/claude-logins/{place}/login") { route.login(call) }
                    post("/api/claude-logins/{place}/refresh") {
                        route.refresh(call, mapOf("claude-splice" to "native-provider"))
                    }
                    get("/api/auth/{head}/login/{id}") {
                        if (!route.poll(call)) call.respondText("unknown", status = HttpStatusCode.NotFound)
                    }
                    post("/api/auth/{head}/login/{id}/code") { route.submit(call) }
                }
            }
            val native = client.post("/api/claude-logins/claude/login") { setBody("""{"label":"named"}""") }
            val separate = client.post("/api/claude-logins/claude-splice/login") { setBody("{}") }
            assertEquals(HttpStatusCode.OK, native.status)
            assertEquals(HttpStatusCode.OK, separate.status)
            val expected = listOf(ClaudeLoginPlaceId.NATIVE to "named", ClaudeLoginPlaceId.SPLICE to null)
            assertEquals(expected, source.requested)
            for (invalid in listOf("not-json", "[]", """{"label":7}""", """{"label":{}}""")) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.post("/api/claude-logins/claude/login") { setBody(invalid) }.status,
                )
            }
            assertEquals(2, source.requested.size)
            val unknown = client.post("/api/claude-logins/wrong/login") { setBody("{}") }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            val refresh = client.post("/api/claude-logins/claude-splice/refresh")
            assertEquals(HttpStatusCode.OK, refresh.status)
            assertTrue(refresh.bodyAsText().contains("\"command\":\"claude-splice\""))
            assertTrue(refresh.bodyAsText().contains("\"carrying_request\":true"), "a refresh keeps the roster's flag")
            val sibling = client.post("/api/claude-logins/claude/refresh").bodyAsText()
            assertTrue(sibling.contains("\"carrying_request\":false"), sibling)
            assertEquals(HttpStatusCode.OK, client.get("/api/auth/claude-splice/login/native-id").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/auth/other/login/native-id").status)
            val code = client.post("/api/auth/claude-splice/login/native-id/code") {
                setBody("""{"code":"synthetic-code"}""")
            }
            assertEquals(HttpStatusCode.OK, code.status)
        }
}
