// two native places proving one subscription cannot invent a second quota after its refusal.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeLoginPlaceView
import splice.accounts.claude.ClaudeLoginPlaces
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadNextTarget
import splice.app.control.NativeUsageSource
import splice.app.probe.UpstreamPlaygroundProbe
import splice.app.provider.ClaudeNativeAccountWiring
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
    fun `a full plain native reading remains probeable on its own`() = runBlocking {
        val fixture = ClaudeNativePoolFixture(home)
        fixture.seed(
            "native",
            quota = QuotaSnapshot(
                fiveHour = QuotaWindow(100.0, 4_102_000_000L, 18_000L),
                sevenDay = QuotaWindow(100.0, 4_102_000_000L, 604_800L),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val files = listOf(home.resolve(".claude/.credentials.json"))
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
        val rig = fixture.rig()
        try {
            val observed = requireNotNull(rig.head.authSurface.accountPool).view(null)
            val announcing = rig.head.copy(
                authSurface = rig.head.authSurface.copy(
                    accountPool = HeadAccountPoolSource { observed.copy(next = HeadNextTarget("new-login")) },
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
}
