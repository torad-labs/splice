// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"; Oct 4, 12:00 AM CT: heads are templates, so this is ANY head built
// from the Claude template, under any key). A client-auth head with accounts in its own folders is a pool: the
// caller's own Claude Code sign-in first, which forwards the caller's credential, then each added account with its
// own. A head with no added account keeps the pre-pool path end to end, which is every install until someone adds one.
package splice.app.provider

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val HEAD = "claude-splice"

class ClaudeAccountPoolArmTest {

    @Test
    fun `a Claude command with no added account keeps the single forwarded login`(@TempDir tmp: Path) = runTest {
        val paths = StatePaths(baseOverride = tmp.resolve("single"))

        val wired = assemble(paths)

        assertTrue(wired.auth is ClientAuthProvider, "the caller's own login still serves the head")
        assertEquals(emptyList<String>(), wired.accounts.map { it.label }, "one login is no choice: no pool")
    }

    @Test
    fun `an added account makes a pool whose first login forwards the caller's own credential`(
        @TempDir tmp: Path,
    ) = runTest {
        val paths = StatePaths(baseOverride = tmp.resolve("pool"))
        add(paths, "work", "uuid-work", "access-work")

        val wired = assemble(paths)

        assertEquals(listOf("claude-code", "work"), wired.accounts.map { it.label })
        val caller = wired.accounts.first()
        assertTrue(caller.primary, "the caller's own sign-in is the primary")
        assertEquals(Credentials.ClientForwarded, caller.auth.credentials(), "it forwards, it is not held")
        assertTrue(caller.auth === wired.auth, "and it is the head's own default credential")
        val added = wired.accounts.last()
        assertFalse(added.primary)
        assertEquals(Credentials.Bearer("access-work"), added.auth.credentials(), "its own folder's token")
    }

    @Test
    fun `each added account gets its own quota file and the folders it was read from`(@TempDir tmp: Path) = runTest {
        val paths = StatePaths(baseOverride = tmp.resolve("files"))
        add(paths, "work", "uuid-work", "access-work")
        add(paths, "home", "uuid-home", "access-home")

        val wired = assemble(paths)

        val files = wired.accounts.map { it.quotaFile }
        assertEquals(files.distinct(), files, "no two logins share a quota snapshot")
        assertEquals(paths.quotaFile(HEAD), wired.accounts.first().quotaFile, "the caller's stays where it was")
        assertTrue(wired.accounts.all { it.credentialPresent })
    }

    @Test
    fun `a Claude command under any key is a template, and an unreadable folder is named, never served`(
        @TempDir tmp: Path,
    ) = runTest {
        val paths = StatePaths(baseOverride = tmp.resolve("template"))
        add(paths, "work", "uuid-work", "access-work", head = "claude")
        val folder = paths.stateDir.resolve("claude-accounts").resolve("claude").resolve("broken")
        Files.createDirectories(folder)
        Files.writeString(folder.resolve(".credentials.json"), "{not json")

        val wired = assemble(paths, head = "claude")

        assertEquals(listOf("claude-code", "work", "broken"), wired.accounts.map { it.label }, "its own row, last")
        val broken = wired.accounts.last()
        assertEquals("this sign-in is unreadable; sign in again", broken.refusal, "on its own card, never another's")
        assertFalse(broken.credentialPresent, "and splice offers no credential for it")
        assertNull(wired.accounts.first { it.label == "work" }.refusal, "the working login is not blamed")
    }

    private fun add(paths: StatePaths, label: String, uuid: String, token: String, head: String = HEAD) {
        val directory = paths.stateDir.resolve("claude-accounts").resolve(head).resolve(label)
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"r-$label","expiresAt":$FAR_FUTURE}}""",
        )
        Files.writeString(directory.resolve(".claude.json"), """{"oauthAccount":{"accountUuid":"$uuid"}}""")
        Files.writeString(directory.resolve(".splice-account.json"), """{"added_at":${ADDED_BASE + label.length}}""")
    }

    private fun assemble(paths: StatePaths, head: String = HEAD): Wired = ProviderAssembly(
        paths,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        LogSink { },
        splice.app.TokenUrlRefreshCall { _, _ -> error("no refresh during assembly") },
    ).buildProvider(build(paths, head))

    private fun build(paths: StatePaths, head: String): ProviderBuild {
        val model = ModelEntry(id = "claude-opus-5-5", contextWindow = 1_000_000)
        return ProviderBuild(
            key = head,
            head = HeadConfig(
                provider = "anthropic",
                port = 3104,
                discoveryPrefix = "$head--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = head, configDir = paths.stateDir.resolve(head).toString()),
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://api.anthropic.com",
                auth = AuthConfig(kind = "client"),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "$head--",
                models = listOf(model),
                defaultContextWindow = model.contextWindow,
            ),
            watchdog = WatchdogBudget(300.seconds, 300.seconds, 900.seconds),
            cfg = ConfigService(paths, headOverrides = mapOf("quotaPoll" to "off")).getConfig(),
            loginCommand = "$head login",
        )
    }
}

private const val FAR_FUTURE = 4_000_000_000_000L
private const val ADDED_BASE = 1_700_000_000_000L
