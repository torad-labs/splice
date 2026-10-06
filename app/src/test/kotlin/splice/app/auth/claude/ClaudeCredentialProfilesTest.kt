// NEW: synthetic provider profiles prove identity for the exact credential, without preserving private profile fields.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeProfileState
import splice.client.ClaudeHead
import splice.client.ClaudeLoginTarget
import splice.core.auth.CredentialKey
import splice.usage.quota.ClientUserAgent
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeCredentialProfilesTest {
    @TempDir
    lateinit var state: Path

    private fun key(token: String): String =
        requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

    @Test
    fun `profile state is scoped to the current credential and survives a refused token restart`() {
        val profiles = ClaudeCredentialProfiles(state, {})
        val pending = key("synthetic-pending")
        val verified = key("synthetic-verified")
        val refused = key("synthetic-refused")
        assertEquals(ClaudeProfileState.PENDING, profiles.state(pending))
        profiles.observed(verified, ClaudeAccountIdentity("synthetic-account", null))
        profiles.failed(refused)
        assertEquals(ClaudeProfileState.VERIFIED, profiles.state(verified))
        assertEquals(ClaudeProfileState.REFUSED, profiles.state(refused))
        val restarted = ClaudeCredentialProfiles(state, {})
        assertEquals(ClaudeProfileState.REFUSED, restarted.state(refused))
        assertEquals(ClaudeProfileState.PENDING, restarted.state(pending), "another token inherits no refusal")
        val folder = Files.createDirectories(state.resolve("synthetic-login"))
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-refused"}}""",
        )
        val location = ClaudeLoginLocation(
            ClaudeLoginPlaceId.NATIVE,
            ClaudeLoginTarget(ClaudeHead("synthetic-head", folder), folder.resolve("account.json")),
            state.resolve("copies"),
        )
        val reader = ClaudeLoginFactsReader(restarted)
        assertEquals(ClaudeProfileState.REFUSED, reader.read(location).profileState)
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-verified"}}""",
        )
        assertEquals(ClaudeProfileState.VERIFIED, reader.read(location).profileState)
    }

    @Test
    fun `a permanent runtime refusal remains refused when its durable cache cannot be written`() = runTest {
        Files.writeString(state.resolve("claude-credential-identities"), "synthetic obstructing file")
        val profiles = ClaudeCredentialProfiles(state, {})
        val refresh = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall { null },
            {},
        )
        val digest = key("synthetic-unpersisted-refusal")
        assertNull(refresh.request(digest, "synthetic-unpersisted-refusal").await())
        assertEquals(ClaudeProfileState.REFUSED, refresh.state(digest))
        assertEquals(
            ClaudeProfileState.PENDING,
            profiles.state(digest),
            "disk alone cannot report the unwritten refusal",
        )
    }

    @Test
    fun `a corrupt saved profile is pending rather than a fabricated refusal`() {
        val profiles = ClaudeCredentialProfiles(state, {})
        val digest = key("synthetic-corrupt")
        profiles.failed(digest)
        val file = state.resolve("claude-credential-identities").resolve("$digest.json")
        Files.writeString(file, "{ broken")
        assertEquals(ClaudeProfileState.PENDING, profiles.state(digest))
    }

    @Test
    fun `in flight and transient backoff profile reads remain pending without another provider call`() = runTest {
        val profiles = ClaudeCredentialProfiles(state, {})
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall {
                calls++
                release.await()
                throw java.io.IOException("synthetic transient body must not leave")
            },
            {},
        )
        val digest = key("synthetic-active")
        val pending = refresh.request(digest, "synthetic-active")
        runCurrent()
        assertEquals(ClaudeProfileState.PENDING, refresh.state(digest))
        release.complete(Unit)
        assertNull(pending.await())
        assertEquals(ClaudeProfileState.PENDING, refresh.state(digest))
        repeat(3) { assertNull(refresh.request(digest, "synthetic-active").await()) }
        assertEquals(1, calls)
    }

    @Test
    fun `the product profile uses the supplied bearer and only an observed client User Agent`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/oauth/profile", request.url.encodedPath)
            assertEquals("Bearer synthetic-profile-token", request.headers["Authorization"])
            assertEquals("synthetic-observed-client", request.headers["User-Agent"])
            respond(
                """{"account":{"uuid":"proved","email":"proved@synthetic.test","unused":"do-not-store"},""" +
                    """"organization":{"private":"do-not-store"}}""",
                HttpStatusCode.OK,
            )
        }
        val probe = ClaudeProfileProbe(
            ClientUserAgent { "synthetic-observed-client" },
            {},
            AuthClient { HttpClient(engine) },
        )
        val profile = requireNotNull(probe.read("synthetic-profile-token"))
        val profiles = ClaudeCredentialProfiles(state, {})
        profiles.observed(key("synthetic-profile-token"), profile)
        val file = state.resolve("claude-credential-identities").resolve("${key("synthetic-profile-token")}.json")
        val saved = Files.readString(file)
        assertEquals(setOf("uuid", "email"), Json.parseToJsonElement(saved).jsonObject.keys)
        assertEquals(
            ClaudeAccountIdentity("proved", "proved@synthetic.test"),
            profiles.read(key("synthetic-profile-token")),
        )
        assertFalse(saved.contains("synthetic-profile-token"))
        assertFalse(saved.contains("do-not-store"))
    }

    @Test
    fun `no observed User Agent is invented for a profile and a refusal exposes none of its body`() = runTest {
        val logs = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertNull(request.headers["User-Agent"])
            respond("synthetic-private-profile-body", HttpStatusCode.Forbidden)
        }
        val probe = ClaudeProfileProbe(ClientUserAgent { null }, { logs += it }, AuthClient { HttpClient(engine) })
        assertNull(probe.read("synthetic-profile-token"))
        assertFalse(logs.joinToString().contains("synthetic-profile-token"))
        assertFalse(logs.joinToString().contains("synthetic-private-profile-body"))
    }

    @Test
    fun `a cached verified identity is reused without another provider read for that credential`() = runTest {
        val profiles = ClaudeCredentialProfiles(state, {})
        val identity = ClaudeAccountIdentity("proved", "proved@synthetic.test")
        val digest = key("synthetic-held-token")
        profiles.observed(digest, identity)
        val logs = mutableListOf<String>()
        var calls = 0
        val refused = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall {
                calls++
                null
            },
            { logs += it },
        )
        assertEquals(identity, refused.request(digest, "synthetic-held-token").await())
        val failed = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall {
                calls++
                throw java.io.IOException("synthetic-held-token must not reach a log")
            },
            { logs += it },
        )
        assertEquals(identity, failed.request(digest, "synthetic-held-token").await())
        assertEquals(identity, profiles.read(digest))
        assertEquals(0, calls, "verified cache reuse does not execute either provider fake")
        assertNull(profiles.read(key("synthetic-different-token")))
        assertFalse(logs.joinToString().contains("synthetic-held-token"))
    }

    @Test
    fun `concurrent profile requests for one credential share a single captured provider read`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val profiles = ClaudeCredentialProfiles(state, {})
        val identity = ClaudeAccountIdentity("proved", null)
        val refresh = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall {
                calls++
                release.await()
                identity
            },
            {},
        )
        val first = refresh.request(key("synthetic-token"), "synthetic-token")
        val second = refresh.request(key("synthetic-token"), "synthetic-token")
        assertSame(first, second)
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(identity, first.await())
        assertEquals(identity, profiles.read(key("synthetic-token")))
    }

    @Test
    fun `repeated describes make only one refusing profile request for an unchanged credential`() = runTest {
        val profiles = ClaudeCredentialProfiles(state, {})
        var calls = 0
        val refresh = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall {
                calls++
                null
            },
            {},
        )
        val folder = Files.createDirectories(state.resolve("stored-login"))
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-refused","refreshToken":"synthetic","expiresAt":4102444800000}}""",
        )
        val auth = ClaudeFolderAuth(
            folder,
            refresh = ClaudeTokenRefresh { error("describing never rotates a credential") },
            profiles = profiles,
            identities = refresh,
        )
        repeat(3) {
            auth.describe()
            runCurrent()
        }
        assertEquals(1, calls, "a refusal cannot become one token request per console read")
    }

    @Test
    fun `a transient failure is suppressed in memory but a restarted runtime attempts exactly once`() = runTest {
        val profiles = ClaudeCredentialProfiles(state, {})
        val logs = mutableListOf<String>()
        var calls = 0
        val probe = ClaudeProfileCall {
            calls++
            throw java.io.IOException("synthetic-transient-token must not reach logs")
        }
        fun runtime() = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            probe,
            { logs += it },
        )
        val digest = key("synthetic-transient-token")
        val first = runtime()
        repeat(3) { assertNull(first.request(digest, "synthetic-transient-token").await()) }
        assertEquals(1, calls)
        assertFalse(profiles.attempted(digest), "transient failures cannot persist a refusal")
        assertEquals(ClaudeProfileState.PENDING, profiles.state(digest), "transient backoff remains pending")
        val restarted = runtime()
        repeat(3) { assertNull(restarted.request(digest, "synthetic-transient-token").await()) }
        assertEquals(2, calls)
        assertFalse(profiles.attempted(digest))
        assertFalse(logs.joinToString().contains("synthetic-transient-token"))
    }

    @Test
    fun `an actual profile 403 persists its refusal and a restarted runtime never repeats it`() = runTest {
        val profiles = ClaudeCredentialProfiles(state, {})
        var calls = 0
        val probe = ClaudeProfileProbe(
            ClientUserAgent { null },
            {},
            AuthClient {
                HttpClient(
                    MockEngine {
                        calls++
                        respond("synthetic-private-refusal", HttpStatusCode.Forbidden)
                    },
                )
            },
        )
        fun runtime() = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            probe,
            {},
        )
        val digest = key("synthetic-forbidden-token")
        val first = runtime()
        repeat(3) { assertNull(first.request(digest, "synthetic-forbidden-token").await()) }
        assertEquals(1, calls)
        assertNull(profiles.read(digest))
        assertEquals(ClaudeProfileState.REFUSED, first.state(digest))
        val restarted = runtime()
        repeat(3) { assertNull(restarted.request(digest, "synthetic-forbidden-token").await()) }
        assertEquals(1, calls)
        val file = state.resolve("claude-credential-identities").resolve("$digest.json")
        val saved = Files.readString(file)
        assertFalse(saved.contains("synthetic-forbidden-token"))
        assertFalse(saved.contains("synthetic-private-refusal"))
    }

    @Test
    fun `a transient retry starts once at the fifteen minute boundary and a new digest starts immediately`() = runTest {
        val profiles = ClaudeCredentialProfiles(state, {})
        var now = 0L
        var calls = 0
        val refresh = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall {
                calls++
                throw java.io.IOException("synthetic unavailable")
            },
            {},
            splice.core.util.ElapsedClock { now },
        )
        val digest = key("synthetic-boundary-token")
        assertNull(refresh.request(digest, "synthetic-boundary-token").await())
        now = 899_999L
        assertNull(refresh.request(digest, "synthetic-boundary-token").await())
        assertEquals(1, calls)
        assertNull(refresh.request(key("synthetic-rotated-token"), "synthetic-rotated-token").await())
        assertEquals(2, calls)
        now = 900_000L
        val first = refresh.request(digest, "synthetic-boundary-token")
        assertSame(first, refresh.request(digest, "synthetic-boundary-token"))
        assertNull(first.await())
        assertEquals(3, calls)
        assertNull(refresh.request(digest, "synthetic-boundary-token").await())
        assertEquals(3, calls)
    }

    @Test
    fun `valid successful profiles without an account are definite refusals`() = runTest {
        for (body in listOf("{}", """{"account":{}}""", """{"account":{"uuid":""}}""")) {
            val probe = ClaudeProfileProbe(
                ClientUserAgent { null },
                {},
                AuthClient { HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }) },
            )
            assertEquals(ClaudeProfileResult.Refused, probe.result("synthetic-empty-profile"), body)
        }
    }

    @Test
    fun `a late profile after credential rotation cannot name the replacement credential`() = runTest {
        val release = CompletableDeferred<Unit>()
        val profiles = ClaudeCredentialProfiles(state, {})
        val refresh = ClaudeIdentityRefresh(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            profiles,
            ClaudeProfileCall { token ->
                if (token == "synthetic-old") release.await()
                ClaudeAccountIdentity(if (token == "synthetic-old") "old-account" else "new-account", null)
            },
            {},
        )
        val config = Files.createDirectories(state.resolve("native"))
        val location = ClaudeLoginLocation(
            ClaudeLoginPlaceId.NATIVE,
            ClaudeLoginTarget(ClaudeHead("claude-splice", config), config.resolve(".claude.json")),
            state.resolve("copies"),
        )
        Files.writeString(location.credentials, """{"claudeAiOauth":{"accessToken":"synthetic-old"}}""")
        val reader = ClaudeLoginFactsReader(profiles, refresh)
        assertNull(reader.read(location).account)
        runCurrent()
        Files.writeString(location.credentials, """{"claudeAiOauth":{"accessToken":"synthetic-new"}}""")
        assertNull(reader.read(location).account)
        runCurrent()
        assertEquals("new-account", reader.read(location).account?.uuid)
        release.complete(Unit)
        runCurrent()
        assertEquals("new-account", reader.read(location).account?.uuid)
        assertEquals("old-account", profiles.read(key("synthetic-old"))?.uuid)
    }
}
