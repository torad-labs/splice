package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import splice.dialect.passthrough.PassthroughProvider
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.provider.openai.OpenAiResponsesProvider
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ProviderAssemblyCompatibilityTest {

    private val supported = mapOf(
        AuthKind.ChatgptOAuth to setOf(Dialect.OPENAI_RESPONSES),
        AuthKind.GrokOAuth to setOf(Dialect.OPENAI_RESPONSES, Dialect.OPENAI_CHAT),
        AuthKind.KimiOAuth to setOf(Dialect.ANTHROPIC_PASSTHROUGH),
        AuthKind.Client to setOf(Dialect.ANTHROPIC_PASSTHROUGH),
    )

    @Test
    fun `every registered auth kind is accepted only on its compatible dialects`(@TempDir tmp: Path) = runTest {
        val fixture = Fixture(tmp, backgroundScope)
        assertEquals(AuthKindRegistry.knownKinds().toSet(), supported.keys)
        var accepted = 0
        var rejected = 0

        for (kind in AuthKindRegistry.knownKinds()) {
            val allowedDialects = supported.getValue(kind)
            for (dialect in Dialect.entries) {
                val ctx = fixture.context(kind.wire, dialect)
                if (dialect in allowedDialects) {
                    assertDoesNotThrow(
                        { fixture.assembly.buildProvider(ctx) },
                        "${kind.wire} must remain supported on ${dialectWire(dialect)}",
                    )
                    accepted += 1
                } else {
                    val error = assertThrows(IllegalArgumentException::class.java) {
                        fixture.assembly.buildProvider(ctx)
                    }
                    val message = error.message.orEmpty()
                    assertTrue(message.contains(ctx.key), message)
                    assertTrue(message.contains(kind.wire), message)
                    assertTrue(message.contains(dialectWire(dialect)), message)
                    rejected += 1
                }
            }
        }

        assertEquals(5, accepted)
        assertEquals(7, rejected)
    }

    @Test
    fun `kimi oauth requires the kimi provider id`(@TempDir tmp: Path) = runTest {
        val fixture = Fixture(tmp, backgroundScope)
        val ctx = fixture.context(
            kind = AuthKind.KimiOAuth.wire,
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            provider = "not-kimi",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.assembly.buildProvider(ctx)
        }
        val message = error.message.orEmpty()
        assertTrue(message.contains(ctx.key), message)
        assertTrue(message.contains(AuthKind.KimiOAuth.wire), message)
        assertTrue(message.contains(ctx.head.provider), message)
        assertTrue(message.contains(dialectWire(ctx.providerCfg.dialect)), message)
    }

    @Test
    fun `api-key and unknown auth kinds retain fallback on every dialect`(@TempDir tmp: Path) = runTest {
        val fixture = Fixture(tmp, backgroundScope)

        for (kind in listOf("api-key", "some-future-kind")) {
            for (dialect in Dialect.entries) {
                val ctx = fixture.context(kind, dialect)
                val wired = fixture.assembly.buildProvider(ctx)
                assertTrue(wired.auth is ApiKeyAuthProvider, "$kind must resolve to API-key auth")
                val correctDialectProvider = when (dialect) {
                    Dialect.OPENAI_RESPONSES -> wired.provider is OpenAiResponsesProvider
                    Dialect.OPENAI_CHAT -> wired.provider is OpenAiChatProvider
                    Dialect.ANTHROPIC_PASSTHROUGH -> wired.provider is PassthroughProvider
                }
                assertTrue(correctDialectProvider, "$kind must stay on ${dialectWire(dialect)}")
            }
        }
    }

    private class Fixture(private val tmp: Path, scope: CoroutineScope) {
        private val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        private val config = ConfigService(statePaths)
        val assembly = ProviderAssembly(
            statePaths = statePaths,
            probeScope = scope,
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("test-denied") },
        )

        fun context(
            kind: String,
            dialect: Dialect,
            provider: String = if (kind == AuthKind.KimiOAuth.wire) "kimi" else "provider",
        ): ProviderBuild {
            val key = "head-${kind.replace('-', '_')}-${dialect.name.lowercase()}"
            return ProviderBuild(
                key = key,
                head = HeadConfig(
                    provider = provider,
                    port = 4100,
                    discoveryPrefix = "claude-test--",
                    pinnedModel = "model",
                ),
                providerCfg = ProviderConfig(
                    dialect = dialect,
                    baseUrl = "https://example.invalid",
                    auth = AuthConfig(kind = kind, file = tmp.resolve("auth.json").toString()),
                ),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-test--",
                    models = listOf(ModelEntry(id = "model", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
                cfg = config.getConfig(key),
                loginCommand = "test login",
            )
        }
    }

    private fun dialectWire(dialect: Dialect): String = when (dialect) {
        Dialect.OPENAI_RESPONSES -> "openai-responses"
        Dialect.OPENAI_CHAT -> "openai-chat"
        Dialect.ANTHROPIC_PASSTHROUGH -> "anthropic-passthrough"
    }
}
