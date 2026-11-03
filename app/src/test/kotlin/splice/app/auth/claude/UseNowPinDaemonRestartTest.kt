// NEW: a Use now pin outlives a daemon restart, end to end (Nov 3, 2026). #461 saved the pins and put them back when the
// daemon built the head, and its tests built the pool the same way, but the install of #461 still dropped Marcos's pin
// on account-3: the head's start resets its pool (HeadLifecycle), and that reset cleared the pins it had just restored.
// No test started a head. This one runs the daemon's own path twice over one state folder: the head assembled by
// ManagedHeadFactory, then started, behind the real control plane, with the pin set and read where the console does.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.core.auth.CredentialKey
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import java.nio.file.Files
import java.nio.file.Path

class UseNowPinDaemonRestartTest {
    @TempDir
    lateinit var home: Path

    /** The command's own sign-in and one added account, as an operator who pressed Use now on the second has them. */
    private fun fixture(): ClaudeNativePoolFixture = ClaudeNativePoolFixture(home).also { fixture ->
        fixture.seed("native")
        val folders = ClaudeAccountFolders(fixture.paths.stateDir, now = WallClock { 1000 })
        val pending = folders.pending(NATIVE_HEAD, "added")
        Files.createDirectories(pending.directory)
        Files.writeString(
            pending.directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-added",""" +
                """"refreshToken":"must-not-be-used","expiresAt":4102444800000}}""",
        )
        Files.writeString(pending.directory.resolve(".claude.json"), """{"oauthAccount":{"accountUuid":"stale"}}""")
        val credential = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-added")))
        ClaudeCredentialProfiles(fixture.paths.stateDir, {}).observed(credential, ClaudeAccountIdentity("added", null))
        assertEquals(ClaudeAccountLanding.Added("added"), folders.land(pending))
        QuotaTracker(folders.accounts(NATIVE_HEAD).single().directory.resolve("quota.json")).record(
            QuotaSnapshot(fiveHour = QuotaWindow(27.0, 4_102_000_000L, 18_000L), updatedAt = 1000),
        )
    }

    /** /api/auth's account_pool for the head, as the Accounts page reads it. */
    private suspend fun accountPool(client: HttpClient, rig: ClaudeNativePoolFixture.Rig): JsonObject {
        val response = client.get("http://127.0.0.1:${rig.server.listeningPort}/api/auth") { bearerAuth(rig.key.get()) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue(NATIVE_HEAD).jsonObject
            .getValue("account_pool").jsonObject
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun `a Use now pin is still set after the daemon restarts and starts the head again`() = runBlocking {
        val fixture = fixture()
        HttpClient(Java).use { client ->
            val before = fixture.rig()
            val other = try {
                val pool = accountPool(client, before)
                val pick = requireNotNull(pool.text("next_target_label"))
                // A login free to serve that the pool would not pick by itself: only a pin sends a turn there.
                val serving = pool.getValue("accounts").jsonArray.map { it.jsonObject }
                    .filter { (it["available"] as? JsonPrimitive)?.content == "true" }
                    .mapNotNull { it.text("label") }
                val other = serving.first { it != pick }
                val switch = "http://127.0.0.1:${before.server.listeningPort}/api/auth/$NATIVE_HEAD/switch"
                val pressed = client.post(switch) {
                    bearerAuth(before.key.get())
                    contentType(ContentType.Application.Json)
                    setBody("""{"label":"$other"}""")
                }
                assertEquals(HttpStatusCode.OK, pressed.status, pressed.bodyAsText())
                assertEquals(other, accountPool(client, before).text("pinned_label"), "Use now takes")
                assertNotEquals(pick, other, "the pin moves the command off the login it would have used")
                other
            } finally {
                before.close()
            }

            val after = fixture.rig()
            try {
                val pool = accountPool(client, after)
                assertEquals(other, pool.text("pinned_label"), "the pin outlives the restart")
                assertEquals(other, pool.text("next_target_label"), "the next turn takes the pinned login")
            } finally {
                after.close()
            }
        }
    }
}
