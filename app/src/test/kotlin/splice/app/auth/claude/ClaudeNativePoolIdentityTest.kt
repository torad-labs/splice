// NEW: two native places proving one subscription cannot invent a second quota after its refusal.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.app.control.NativeUsageSource
import splice.app.probe.UpstreamPlaygroundProbe
import splice.core.auth.CredentialKey
import splice.diagnostics.playground.PlaygroundHead
import java.nio.file.Path

class ClaudeNativePoolIdentityTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `a newly announced target cannot borrow the previous generation's primary quota`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home)
        fixture.seed("native")
        fixture.seed("splice", expiresAt = 1L)
        val rig = fixture.rig()
        try {
            val observed = requireNotNull(rig.head.accountPool).view(null)
            val announcing = rig.head.copy(
                accountPool = HeadAccountPoolSource { observed.copy(nextTargetLabel = "new-login") },
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
    fun `native places proving one account share its refusal instead of claiming another free subscription`() = runBlocking {
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
                        """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic shared subscription"}}""",
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
            val pool = requireNotNull(rig.head.accountPool).view(null)
            assertFalse(pool.accounts.any { it.available }, "a second credential is not a second subscription")
        } finally {
            rig.close()
        }
    }
}
