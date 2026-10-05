package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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
import splice.app.control.ControlServer
import splice.core.auth.CredentialKey
import splice.core.config.ConfigService
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.head.usage.QuotaTracker
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
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

    private fun sessions(): SessionSource = object : SessionSource {
        override fun read(): List<SessionRecord> = listOf(
            "moving" to NATIVE_HEAD,
            "staying" to NATIVE_HEAD,
            "idle" to NATIVE_HEAD,
            "unmatched" to NATIVE_HEAD,
            "foreign" to "synthetic-other-head",
        ).map { (session, head) ->
            SessionRecord(
                pid = null,
                sessionId = session,
                cwd = home.toString(),
                name = null,
                kind = null,
                version = null,
                status = SessionStatus(),
                startedAt = null,
                updatedAt = null,
                messagingSocketPath = null,
                route = SessionRoute.Head(head),
                availability = SessionAvailability.LIVE,
            )
        }

        override fun list(): SessionListing = SessionListing(read())
    }

    private suspend fun send(client: HttpClient, rig: ClaudeNativePoolFixture.Rig, session: String) {
        val response = client.post("http://127.0.0.1:${rig.head.head.port}/v1/messages") {
            bearerAuth("synthetic-caller")
            header("x-claude-code-session-id", session)
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"synthetic-model","stream":false,"max_tokens":32,"messages":[{"role":"user","content":"synthetic session turn"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun sessionAccounts(
        client: HttpClient,
        control: ControlServer,
        rig: ClaudeNativePoolFixture.Rig,
    ): Map<String, String?> {
        val response = client.get("http://127.0.0.1:${control.listeningPort}/api/sessions") {
            bearerAuth(rig.key.get())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val sessions = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("sessions").jsonArray
        return sessions.associate { row ->
            val session = row.jsonObject
            session.getValue("session_id").jsonPrimitive.content to
                session.getValue("account").jsonPrimitive.contentOrNull
        }
    }

    private fun sessionControl(fixture: ClaudeNativePoolFixture, rig: ClaudeNativePoolFixture.Rig): ControlServer =
        ControlServer(
            port = 0,
            heads = mapOf(NATIVE_HEAD to rig.head, "synthetic-other-head" to rig.head),
            config = ConfigService(fixture.paths),
            mgmtKey = rig.key,
            dashboardHtml = { "<!doctype html>" },
            log = {},
            sessions = sessions(),
        )

    @Test
    fun `Sessions names this session's actual added-account send and never another session's choice`() = runBlocking {
        val fixture = fixture()
        val sent = CopyOnWriteArrayList<String?>()
        val upstream = fixture.nativeUpstream(sent, CopyOnWriteArrayList(), refusedCredential = null).start()
        val rig = fixture.rig(upstreamUrl = "http://127.0.0.1:${upstream.engine.resolvedConnectors().single().port}")
        val control = sessionControl(fixture, rig)
        control.ports.claudeLogins = rig.server.ports.claudeLogins
        try {
            control.start()
            HttpClient(Java).use { client ->
                val pool = requireNotNull(rig.head.accountPool)
                val pin = pool as HeadAccountPinSource
                assertTrue(pin.pin(NATIVE_SELECTOR))
                send(client, rig, "moving")
                send(client, rig, "staying")
                assertTrue(pin.pin("added"))
                send(client, rig, "moving")
                rig.plane.sentCredentials.sent(NATIVE_HEAD, "unmatched", key("synthetic-unlisted"))
                assertEquals(
                    listOf("Bearer synthetic-native", "Bearer synthetic-native", "Bearer synthetic-added"),
                    sent,
                )
                val expected = mapOf(
                    "moving" to "added",
                    "staying" to "claude",
                    "idle" to null,
                    "unmatched" to null,
                    "foreign" to null,
                )
                assertEquals(expected, sessionAccounts(client, control, rig))
                assertEquals("added", pool.view("moving").selectedLabel)
                rig.plane.sentCredentials.sent(NATIVE_HEAD, "moving", key("synthetic-unlisted"))
                assertEquals(
                    expected + ("moving" to null),
                    sessionAccounts(client, control, rig),
                    "a pool choice must not substitute for the newest unmatched sent credential",
                )
            }
        } finally {
            control.stop()
            rig.close()
            upstream.stop()
        }
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
