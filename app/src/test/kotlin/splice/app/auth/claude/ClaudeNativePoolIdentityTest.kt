// two native places proving one subscription cannot invent a second quota after its refusal.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.app.control.NativeUsageSource
import splice.app.probe.UpstreamPlaygroundProbe
import splice.app.provider.ClaudeNativeAccountWiring
import splice.core.auth.CredentialKey
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.diagnostics.playground.PlaygroundHead
import java.nio.file.Files
import java.nio.file.Path

class ClaudeNativePoolIdentityTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `a credential change while wiring cannot relabel the captured login's full reading`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home)
        fixture.seed(
            "native",
            quota = QuotaSnapshot(
                sevenDay = QuotaWindow(100.0, 4_102_000_000L, 604_800L),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        fixture.seed("splice", expiresAt = 1L)
        val rig = fixture.rig()
        try {
            val owner = requireNotNull(rig.server.ports.claudeLogins)
            var replaced = false
            val rotating = object : ClaudeLoginPlaces by owner {
                override fun places(): List<ClaudeLoginPlaceView> {
                    val captured = owner.places()
                    if (!replaced) {
                        replaced = true
                        fixture.replaceNative()
                    }
                    return captured
                }
            }
            val wiring = ClaudeNativeAccountWiring(fixture.paths, ClaudeLoginPlacesSource { rotating }, {})
            val login = wiring.accounts(NATIVE_HEAD).single { it.label == NATIVE_SELECTOR }
            assertNull(
                login.quota.read?.snapshot(),
                "the replacement has no reading; an orphaned credential cannot lend it a full window",
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a full plain native reading remains probeable beside an expired primary`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home)
        fixture.seed(
            "native",
            quota = QuotaSnapshot(
                fiveHour = QuotaWindow(100.0, 4_102_000_000L, 18_000L),
                sevenDay = QuotaWindow(100.0, 4_102_000_000L, 604_800L),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        fixture.seed("splice", expiresAt = 1L)
        val files = listOf(home.resolve(".claude/.credentials.json"), home.resolve(".claude-splice/.credentials.json"))
        val before = files.map(Files::readAllBytes)
        val rig = fixture.rig()
        try {
            val sent = mutableListOf<String?>()
            HttpClient(
                MockEngine {
                    sent += it.headers["Authorization"]
                    respond("{}", HttpStatusCode.OK)
                },
            ).use { client ->
                val probe = UpstreamPlaygroundProbe(rig.plane.playgroundProviders, client)
                repeat(3) {
                    probe.run(PlaygroundHead(NATIVE_HEAD, rig.head.auth), "synthetic full reading", null)
                }
            }
            assertEquals(List(3) { "Bearer synthetic-native" }, sent, "the expired login is never sent")
            files.forEachIndexed { index, file -> assertArrayEquals(before[index], Files.readAllBytes(file)) }
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a newly announced target cannot borrow the previous generation's primary quota`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home)
        fixture.seed("native")
        fixture.seed("splice", expiresAt = 1L)
        val rig = fixture.rig()
        try {
            val observed = requireNotNull(rig.head.authSurface.accountPool).view(null)
            val announcing = rig.head.copy(
                authSurface = rig.head.authSurface.copy(
                    accountPool = HeadAccountPoolSource { observed.copy(nextTargetLabel = "new-login") },
                ),
            )
            val source = NativeUsageSource(
                announcing,
                ClaudeLoginPlacesSource { rig.server.ports.claudeLogins },
            )
            assertNull(source.snapshot().quota, "the new target has no observation in the captured account generation")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `native places proving one account share its refusal, not claiming another free subscription`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home)
        fixture.seed("native")
        fixture.seed("splice")
        val key = requireNotNull(
            CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-splice")),
        )
        val profiles = ClaudeCredentialProfiles(fixture.paths.stateDir, {})
        profiles.observed(key, ClaudeAccountIdentity("account-native", null))
        val rig = fixture.rig()
        try {
            HttpClient(
                MockEngine {
                    respond(
                        """{"type":"error","error":{"type":"rate_limit_error",""" +
                            """"message":"synthetic shared subscription"}}""",
                        HttpStatusCode.TooManyRequests,
                        headersOf(
                            "anthropic-ratelimit-unified-status" to listOf("rejected"),
                            "anthropic-ratelimit-unified-representative-claim" to listOf("seven_day"),
                            "anthropic-ratelimit-unified-reset" to listOf(
                                (System.currentTimeMillis() / 1_000L + 3_600L).toString(),
                            ),
                        ),
                    )
                },
            ).use { client ->
                UpstreamPlaygroundProbe(rig.plane.playgroundProviders, client)
                    .run(PlaygroundHead(NATIVE_HEAD, rig.head.auth), "synthetic shared account", null)
            }
            assertTrue(rig.head.head.providerResetForMs() > 0L, "status must see the shared refusal before a pool view")
            val pool = requireNotNull(rig.head.authSurface.accountPool).view(null)
            assertFalse(pool.accounts.any { it.available }, "a second credential is not a second subscription")
        } finally {
            rig.close()
        }
    }
}
