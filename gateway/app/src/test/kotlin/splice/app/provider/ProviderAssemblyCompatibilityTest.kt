package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.SignInPlanner
import splice.app.TokenUrlRefreshCall
import splice.core.auth.Credentials
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
import splice.core.topology.QuirksConfig
import splice.core.turn.WatchdogBudget
import splice.dialect.passthrough.PassthroughProvider
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.provider.openai.OpenAiResponsesProvider
import java.nio.file.Files
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

    @Test
    fun `ChatGPT assembly reads each resolved provider auth file`(@TempDir tmp: Path) = runTest {
        val fixture = Fixture(tmp, backgroundScope)
        for (name in listOf("baseline", "code_mode")) {
            val authFile = tmp.resolve("$name.json")
            Files.writeString(authFile, """{"tokens":{"access_token":"synthetic-$name","account_id":"$name"}}""")
            val ctx = fixture.context(AuthKind.ChatgptOAuth.wire, Dialect.OPENAI_RESPONSES)
            val wired = fixture.assembly.buildProvider(
                ctx.copy(
                    key = name,
                    providerCfg = ctx.providerCfg.copy(
                        auth = ctx.providerCfg.auth.copy(file = authFile.toString()),
                    ),
                ),
            )
            val credentials = wired.auth.credentials() as? Credentials.Bearer
            assertEquals(Credentials.Bearer("synthetic-$name", name), credentials)
        }
    }

    @Test
    fun `ChatGPT assembly retains default auth path when provider file is absent`(@TempDir tmp: Path) = runTest {
        val fixture = Fixture(tmp, backgroundScope)
        val ctx = fixture.context(AuthKind.ChatgptOAuth.wire, Dialect.OPENAI_RESPONSES)
        Files.writeString(Path.of(ctx.cfg.codexAuthPath), """{"tokens":{"access_token":"synthetic-default"}}""")
        val wired = fixture.assembly.buildProvider(
            ctx.copy(providerCfg = ctx.providerCfg.copy(auth = ctx.providerCfg.auth.copy(file = null))),
        )
        assertEquals(Credentials.Bearer("synthetic-default"), wired.auth.credentials())
    }

    @Test
    fun `legacy auth resolution preserves environment and runtime overrides`(@TempDir tmp: Path) = runTest {
        val fixture = Fixture(tmp, backgroundScope)
        val declared = fixture.context(AuthKind.ChatgptOAuth.wire, Dialect.OPENAI_RESPONSES)
        val environmentPath = tmp.resolve("environment.json").toString()
        val runtimePath = tmp.resolve("runtime.json").toString()
        val config = ConfigService(
            StatePaths(baseOverride = tmp.resolve("legacy-state")),
            headOverrides = mapOf("codexAuthPath" to checkNotNull(declared.providerCfg.auth.file)),
            envReader = { if (it == "CODEX_AUTH_PATH") environmentPath else null },
        )
        val inputs = HeadBuildInputs(config, SignInPlanner())
        fun resolved() = inputs.resolveProviderConfig(declared.providerCfg, config.getConfig(declared.key)).auth.file

        assertEquals(environmentPath, resolved())
        config.patch(mapOf("codexAuthPath" to runtimePath))
        assertEquals(runtimePath, resolved())
    }

    @Test
    fun `code mode accepts omitted false and true for ChatGPT responses`() {
        for (enabled in listOf(null, false, true)) {
            assertDoesNotThrow {
                ProviderConfig(
                    dialect = Dialect.OPENAI_RESPONSES,
                    baseUrl = "https://example.invalid",
                    auth = AuthConfig(kind = AuthKind.ChatgptOAuth.wire),
                    quirks = QuirksConfig(codeMode = enabled),
                )
            }
        }
    }

    @Test
    fun `code mode rejects non ChatGPT responses at construction`() {
        val unsupported = listOf(
            "ChatGPT wrong dialect" to (AuthKind.ChatgptOAuth.wire to Dialect.OPENAI_CHAT),
            "Grok" to (AuthKind.GrokOAuth.wire to Dialect.OPENAI_RESPONSES),
            "Kimi" to (AuthKind.KimiOAuth.wire to Dialect.ANTHROPIC_PASSTHROUGH),
            "Claude client" to (AuthKind.Client.wire to Dialect.ANTHROPIC_PASSTHROUGH),
            "api-key" to ("api-key" to Dialect.OPENAI_RESPONSES),
            "local" to ("local" to Dialect.OPENAI_RESPONSES),
            "none" to ("none" to Dialect.OPENAI_RESPONSES),
            "custom auth" to ("workspace-oauth" to Dialect.OPENAI_RESPONSES),
        )

        unsupported.forEach { (label, authAndDialect) ->
            val (kind, dialect) = authAndDialect
            for (enabled in listOf(null, false)) {
                assertDoesNotThrow(
                    {
                        ProviderConfig(
                            dialect = dialect,
                            baseUrl = "https://example.invalid",
                            auth = AuthConfig(kind = kind),
                            quirks = QuirksConfig(codeMode = enabled),
                        )
                    },
                    "$label: code_mode=$enabled",
                )
            }
            val error = assertThrows(IllegalArgumentException::class.java) {
                ProviderConfig(
                    dialect = dialect,
                    baseUrl = "https://example.invalid",
                    auth = AuthConfig(kind = kind),
                    quirks = QuirksConfig(codeMode = true),
                )
            }
            assertTrue(error.message.orEmpty().contains("code_mode"), "$label: ${error.message}")
            assertTrue(error.message.orEmpty().contains(AuthKind.ChatgptOAuth.wire), "$label: ${error.message}")
            assertTrue(error.message.orEmpty().contains("openai-responses"), "$label: ${error.message}")
        }
    }

    private class Fixture(private val tmp: Path, scope: CoroutineScope) {
        private val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        private val config = ConfigService(
            statePaths,
            headOverrides = mapOf("codexAuthPath" to tmp.resolve("missing-auth.json").toString()),
            envReader = { null },
        )
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
