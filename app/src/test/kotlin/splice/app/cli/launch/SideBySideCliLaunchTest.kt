package splice.app.cli.launch

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.Command
import splice.app.cli.CommandParser
import splice.app.desktop.catalog.CatalogBuild
import splice.app.desktop.catalog.CatalogFile
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.PublishedCatalog
import splice.app.desktop.catalog.SelectorAliases
import splice.app.desktop.lane.TerminalDoor
import splice.app.provider.ProviderLogin
import splice.app.provider.ServedProvider
import splice.app.provider.ServingProviders
import splice.client.resume.AuthHeaderFile
import splice.client.resume.ResumeHookTarget
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthKind
import splice.core.topology.Topology
import splice.core.usage.QuotaFull
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.head.GatewayTurn
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

class SideBySideCliLaunchTest {
    @Test
    fun `CommandParser parses splice launch claude and claude-desktop`() {
        val parser = CommandParser()
        val claudeArgs = arrayOf("launch", "claude", "-p", "hi")
        assertEquals(Command.Launch(listOf("claude", "-p", "hi")), parser.parse(claudeArgs))
        assertEquals(Command.Launch(listOf("claude-desktop")), parser.parse(arrayOf("launch", "claude-desktop")))
        assertTrue(parser.help(arrayOf("launch", "--help"))?.contains("claude-desktop") == true)
    }

    @Test
    fun `launchSideBySide attaches login, speed and session-start hooks in overlay without touching claude dir`(
        @TempDir home: Path,
    ) = runBlocking {
        UserHome.within(home) {
            val env = EnvReader { null }
            val log = LogSink {}
            val statePaths = StatePaths(envReader = env)
            val published = PublishedCatalog(
                CatalogBuild(),
                CatalogFile(statePaths.modelCatalogFile, log),
                kotlinx.coroutines.Dispatchers.IO,
            )
            val header = Files.createDirectories(statePaths.stateDir).resolve("turn-auth-header")
            Files.writeString(header, "Authorization: Bearer test\n")
            val hookTarget = ResumeHookTarget(3096, AuthHeaderFile { header })
            val providers = DesktopProviders(
                serving = ServingProviders { listOf(fakeChatGptProvider()) },
                desktopOnly = ServingProviders { emptyList() },
                aliases = SelectorAliases(emptyList()),
                published = published,
            )
            val terminal = TerminalDoor(Topology(), statePaths, log)
            terminal.open(providers, "http://127.0.0.1:3096/door", hookTarget)
            val spec = requireNotNull(terminal.launchSideBySide())
            assertNotNull(spec)
            assertEquals("http://127.0.0.1:3096/door", spec.env["ANTHROPIC_BASE_URL"])
            assertFalse(
                "DISABLE_LOGIN_COMMAND" in spec.env,
                "side-by-side must keep Claude Code /login enabled when ~/.claude/commands/login.md is absent",
            )
            assertFalse(
                Files.exists(home.resolve(".claude/commands/login.md")),
                "side-by-side launch must never write into ~/.claude",
            )
            assertFalse(
                Files.exists(home.resolve(".claude")),
                "side-by-side launch must not even create ~/.claude",
            )
            val overlay = Json.parseToJsonElement(spec.overlay).jsonObject
            val hooks = overlay["hooks"]!!.jsonObject
            val ups = hooks["UserPromptSubmit"]!!.jsonArray.toString()
            val start = hooks["SessionStart"]!!.jsonArray.toString()
            assertTrue("splice-login-hook.sh" in ups, "overlay must carry /login hook: $ups")
            assertTrue("splice-speed-hook.sh" in ups, "overlay must carry /fast hook when ChatGPT is active: $ups")
            assertTrue("splice-door-start-hook.sh" in start, "overlay must carry SessionStart hook: $start")
            val speedScript = Files.readString(statePaths.doorLoginDir.resolve("splice-speed-hook.sh"))
            assertTrue("/hooks/speed/door" in speedScript, speedScript)
            val startScript = Files.readString(statePaths.doorLoginDir.resolve("splice-door-start-hook.sh"))
            assertTrue("/hooks/resume/door" in startScript, startScript)
        }
    }

    @Test
    fun `launchSideBySide omits speed hook when no ChatGPT provider is in the lineup`(
        @TempDir home: Path,
    ) = runBlocking {
        UserHome.within(home) {
            val env = EnvReader { null }
            val log = LogSink {}
            val statePaths = StatePaths(envReader = env)
            val published = PublishedCatalog(
                CatalogBuild(),
                CatalogFile(statePaths.modelCatalogFile, log),
                kotlinx.coroutines.Dispatchers.IO,
            )
            val header = Files.createDirectories(statePaths.stateDir).resolve("turn-auth-header")
            Files.writeString(header, "Authorization: Bearer test\n")
            val hookTarget = ResumeHookTarget(3096, AuthHeaderFile { header })
            val providers = DesktopProviders(
                serving = ServingProviders { emptyList() },
                desktopOnly = ServingProviders { emptyList() },
                aliases = SelectorAliases(emptyList()),
                published = published,
            )
            val terminal = TerminalDoor(Topology(), statePaths, log)
            terminal.open(providers, "http://127.0.0.1:3096/door", hookTarget)
            val spec = requireNotNull(terminal.launchSideBySide())
            val hooks = Json.parseToJsonElement(spec.overlay).jsonObject["hooks"]!!.jsonObject
            val ups = hooks["UserPromptSubmit"]!!.jsonArray.toString()
            assertTrue("splice-login-hook.sh" in ups, ups)
            assertFalse("splice-speed-hook.sh" in ups, "no ChatGPT provider -> no speed hook: $ups")
            assertTrue("splice-door-start-hook.sh" in hooks["SessionStart"]!!.jsonArray.toString())
        }
    }

    private fun fakeChatGptProvider(): ServedProvider = object : ServedProvider {
        override val key: String = "codex"
        override val authKind: String = AuthKind.ChatgptOAuth.wire
        override val catalog: ModelCatalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            pinnedModel = "gpt-5.6-sol",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "GPT-5.6", contextWindow = 200_000L)),
            defaultContextWindow = 200_000L,
        )
        override val tiers: Map<String, String> = emptyMap()
        override val pinnedModel: String = "gpt-5.6-sol"
        override val dialect: String = "openai-responses"
        override val usage: HeadUsageSource = HeadUsageSource { UsageView(0L, 0, null) }
        override val usesLogin: Boolean = true
        override val forwardsClientAuth: Boolean = false
        override val maxRequestBytes: Int = 1_000_000
        override val requestReadTimeoutMs: Long = 10_000L
        override fun quotaFull(): QuotaFull? = null
        override suspend fun login(): ProviderLogin = ProviderLogin.Ready
        override suspend fun serve(call: ApplicationCall, turn: GatewayTurn) = Unit
        override suspend fun countTokens(call: ApplicationCall, turn: GatewayTurn) = Unit
    }
}
