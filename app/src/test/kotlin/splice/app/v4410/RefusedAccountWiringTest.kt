// NEW: V4-410 — the pool credential splice refuses (a symlinked <label>.json, V4-405) reaches the head's account
// surface as refused, in words, and nothing opens it. The wiring is the real Codex arm over a real pool dir. The
// link's target is a VALID labeled credential: had any reader followed the link, its account would describe as
// signed in, so present=false through the arm is the proof that it was never opened. An orphan (a quota with no
// credential) carries no refusal, so it still reads as a missing credential the console can renew.
package splice.app.v4410

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.app.head.HeadAccountPools
import splice.app.provider.CodexResponsesArm
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.core.turn.WatchdogBudget
import splice.oauth.OAuthAccountFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class RefusedAccountWiringTest {
    private val kind = AuthKind.ChatgptOAuth
    private val files = OAuthAccountFiles()

    private fun credential(token: String) = Json.parseToJsonElement(
        """{"tokens":{"access_token":"$token","account_id":"$token-id"}}""",
    ).jsonObject

    /** primary and backup are real credentials; `linked` is a symlink to another valid one; `lost` is a bare quota. */
    private fun populate(tmp: Path): Path {
        val primary = tmp.resolve("auth.json")
        Files.writeString(primary, credential("synthetic-primary").toString())
        files.writeLabeled(kind, primary, "backup", credential("synthetic-backup"))
        val target = tmp.resolve("someone-elses-credential.json")
        Files.writeString(target, files.decorated(kind, "linked", credential("synthetic-linked")).toString())
        val pool = files.poolDir(kind, primary)
        Files.createSymbolicLink(pool.resolve("linked.json"), target)
        Files.writeString(pool.resolve("lost-quota.json"), "{}")
        return primary
    }

    private fun wire(scope: CoroutineScope, tmp: Path, primary: Path): Wired =
        CodexResponsesArm(
            StatePaths(baseOverride = tmp.resolve("state")).also { Files.createDirectories(it.stateDir) },
            scope,
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("test-denied") },
        ).codexOAuthProvider(context(tmp, primary), "claude-codex")

    @Test
    fun `a linked credential wires as refused with no credential and its valid target is never read`(
        @TempDir tmp: Path,
    ) = runTest {
        val wired = wire(backgroundScope, tmp, populate(tmp))

        assertEquals(listOf("primary", "backup", "linked", "lost"), wired.accounts.map { it.label })
        val linked = wired.accounts.single { it.label == "linked" }
        assertFalse(linked.credentialPresent)
        assertTrue(linked.refusal.orEmpty().contains("symbolic link"), linked.refusal)
        assertEquals(false, linked.auth.describe().present, "the link's target is a valid credential; it was opened")
        assertEquals(null, linked.auth.credentials())
        assertFalse(linked.auth.describe().fields.values.any { it.contains("someone-elses-credential") })
    }

    @Test
    fun `an orphan wires as a missing credential with no refusal`(@TempDir tmp: Path) = runTest {
        val wired = wire(backgroundScope, tmp, populate(tmp))

        val lost = wired.accounts.single { it.label == "lost" }
        assertFalse(lost.credentialPresent)
        assertEquals(null, lost.refusal)
        assertEquals(false, lost.auth.describe().present)
        val loaded = wired.accounts.filter { it.label in setOf("primary", "backup") }
        assertEquals(listOf(true, true), loaded.map { it.credentialPresent })
    }

    @Test
    fun `the head's account descriptions carry the refusal for the linked account alone`(@TempDir tmp: Path) = runTest {
        val wired = wire(backgroundScope, tmp, populate(tmp))

        val described = requireNotNull(HeadAccountPools().authSource(wired)).descriptions()

        val refusal = wired.accounts.single { it.label == "linked" }.refusal
        assertTrue(refusal.orEmpty().contains("symbolic link"), refusal)
        assertEquals(refusal, described.getValue("linked").fields["refusal"])
        assertEquals(false, described.getValue("linked").present)
        assertNotNull(described.getValue("linked").fields["auth_path"])
        assertEquals(
            listOf(false, false, false),
            listOf("primary", "backup", "lost").map { described.getValue(it).fields.containsKey("refusal") },
        )
        assertEquals(false, described.getValue("lost").present)
    }

    private fun context(tmp: Path, authFile: Path): ProviderBuild {
        val key = "codex"
        val config = ConfigService(StatePaths(baseOverride = tmp.resolve("state")), envReader = { null })
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = "codex",
                port = 3100,
                discoveryPrefix = "claude-codex--",
                pinnedModel = "gpt-6-astra",
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = kind.wire, file = authFile.toString()),
                quirks = QuirksConfig(accountIdHeader = true, store = true),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry(id = "gpt-6-astra", contextWindow = 1_000_000)),
                defaultContextWindow = 1_000_000,
            ),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = config.getConfig(key),
            loginCommand = "claude-codex login",
        )
    }
}
