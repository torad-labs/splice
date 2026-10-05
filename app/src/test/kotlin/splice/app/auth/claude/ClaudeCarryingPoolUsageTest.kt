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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.pool.HeadAccountPinSource
import splice.core.auth.CredentialKey
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class ClaudeCarryingPoolUsageTest {
    @TempDir
    lateinit var home: Path

    private fun key(token: String): String =
        requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

    private fun add(fixture: ClaudeNativePoolFixture) {
        val folders = ClaudeAccountFolders(fixture.paths.stateDir, now = WallClock { 1000 })
        val pending = folders.pending(NATIVE_HEAD, "added")
        Files.createDirectories(pending.directory)
        Files.writeString(
            pending.directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-added","refreshToken":"must-not-be-used","expiresAt":4102444800000}}""",
        )
        Files.writeString(pending.directory.resolve(".claude.json"), """{"oauthAccount":{"accountUuid":"stale"}}""")
        val credential = key("synthetic-added")
        ClaudeCredentialProfiles(fixture.paths.stateDir, {})
            .observed(credential, ClaudeAccountIdentity("added-account", null))
        assertEquals(ClaudeAccountLanding.Added("added"), folders.land(pending))
        val account = folders.accounts(NATIVE_HEAD).single()
        QuotaTracker(account.directory.resolve("quota.json")).record(
            QuotaSnapshot(fiveHour = QuotaWindow(27.0, 4_102_000_000L, 18_000L), updatedAt = 1000),
        )
    }

    private fun fixture(): ClaudeNativePoolFixture = ClaudeNativePoolFixture(home).also { fixture ->
        fixture.seed(
            "native",
            quota = QuotaSnapshot(fiveHour = QuotaWindow(91.0, 4_102_000_000L, 18_000L), updatedAt = 1000),
        )
        add(fixture)
    }

    @Test
    fun `an added pool account clears the old carrying place without replacing its own Usage quota`() = runBlocking {
        val fixture = fixture()
        val sent = CopyOnWriteArrayList<String?>()
        val upstream = fixture.nativeUpstream(sent, CopyOnWriteArrayList(), refusedCredential = null).start()
        val port = upstream.engine.resolvedConnectors().single().port
        val rig = fixture.rig(upstreamUrl = "http://127.0.0.1:$port")
        try {
            val owner = requireNotNull(rig.server.ports.claudeLogins)
            rig.plane.sentCredentials.sent(NATIVE_HEAD, "moving", key("synthetic-native"))
            rig.plane.sentCredentials.sent(NATIVE_HEAD, "staying", key("synthetic-native"))
            assertEquals(ClaudeLoginPlaceId.NATIVE, owner.carrying(NATIVE_HEAD, "moving"))
            val pool = requireNotNull(rig.head.accountPool)
            val accounts = pool.view(null).accounts
            assertEquals(setOf(NATIVE_SELECTOR, SPLICE_SELECTOR, "added"), accounts.map { it.label }.toSet())
            assertEquals(false, accounts.single { it.label == SPLICE_SELECTOR }.credentialPresent)
            assertTrue((pool as HeadAccountPinSource).pin("added"))
            HttpClient(Java).use { client ->
                val turn = client.post("http://127.0.0.1:${rig.head.head.port}/v1/messages") {
                    bearerAuth("synthetic-caller")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"model":"synthetic-model","stream":false,"max_tokens":32,"messages":[{"role":"user","content":"synthetic pool turn"}]}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, turn.status, turn.bodyAsText())
                assertEquals(listOf("Bearer synthetic-added"), sent)
                rig.plane.sentCredentials.sent(NATIVE_HEAD, "moving", key("synthetic-added"))
                assertNull(owner.carrying(NATIVE_HEAD, "moving"))
                assertEquals(ClaudeLoginPlaceId.NATIVE, owner.carrying(NATIVE_HEAD, "staying"))

                val response = client.get("http://127.0.0.1:${rig.server.listeningPort}/api/usage") {
                    bearerAuth(rig.key.get())
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val head = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    .getValue("heads").jsonArray.single().jsonObject
                assertEquals("added", pool.view(null).nextTargetLabel)
                val usage = head.getValue("usage").jsonObject
                assertTrue("quota" in usage, "the selected added account needs its own quota: $head")
                val fiveHour = usage.getValue("quota").jsonObject.getValue("five_hour").jsonObject
                assertEquals("27", fiveHour.getValue("used_pct").jsonPrimitive.content)
            }
        } finally {
            rig.close()
            upstream.stop()
        }
    }
}
