// NEW: CodexResponsesArm owns ChatGPT OAuth accounts, /responses URL, routing headers, quirks, and
// code-mode default-on. Shared ResponsesArm only dispatches.
package splice.app.provider

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Stall
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRuntimeMetadata
import splice.core.parse.AnthropicParse
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ResponsesFunctionNamespace
import splice.oauth.OAuthAccountFiles
import splice.upstream.BuiltTurn
import splice.upstream.ReanchorRound
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class CodexResponsesArmTest {

    @Test
    fun `codex oauth path pins responses url routing quirks accounts and code-mode on`(@TempDir tmp: Path) = runTest {
        val primaryFile = writeAccounts(tmp)
        // V4-441: exec comes from the backend's own mark, which discovery carried from its model list.
        val ctx = context(tmp, primaryFile, listOf(ASTRA_LISTED))
        assertTrue(ctx.providerCfg.codeModeEnabled)
        val wired = CodexResponsesArm(
            StatePaths(baseOverride = tmp.resolve("state")).also { Files.createDirectories(it.stateDir) },
            backgroundScope,
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("test-denied") },
        ).codexOAuthProvider(ctx, "claude-codex")
        pinAccounts(wired)
        val built = wired.provider.buildTurn(AnthropicParse.parseAnthropicBody(TOOL_BODY), false, "session-1")
        pinTurn(built)
        // Failure-handling slice 4: the models a fold continues are the ones codex's backend cuts (CodexReplies), and the
        // fold knobs that once added gpt-6-astra here retired in 0.4.0, so a turn on it is never folded.
        assertNull(wired.provider.foldPolicy(built.meta))
        wired.provider.onHeadStop()
    }

    @Test
    fun `Codex re-anchor keeps partial prose phase-less`(@TempDir tmp: Path) = runTest {
        val wired = CodexResponsesArm(
            StatePaths(baseOverride = tmp.resolve("state")).also { Files.createDirectories(it.stateDir) },
            backgroundScope,
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("test-denied") },
        ).codexOAuthProvider(context(tmp, writeAccounts(tmp)), "claude-codex")
        try {
            val standardBody = TOOL_BODY.replace("gpt-6-astra", "gpt-5-codex")
            val built = wired.provider.buildTurn(AnthropicParse.parseAnthropicBody(standardBody), false, "session-1")
            val continued = checkNotNull(wired.provider.reanchorPolicy(built.meta)).continuationForFailure(
                ReanchorRound(
                    built.requestBody,
                    TurnOutcome.Failure(
                        cause = FailureCause.Path.Stalled(Stall.IDLE),
                        at = Position(Moment.MID_CONTENT, streamBegan = true),
                        evidence = FailureEvidence("test interruption"),
                        partial = TurnOutcome.PartialRound(text = RoundText(bodyText = "partial answer")),
                    ),
                    attempt = 0,
                ),
            )
            val added = checkNotNull(continued).getValue("input").jsonArray
                .drop(built.requestBody.getValue("input").jsonArray.size).map { it.jsonObject }
            val partial = added.single { it["content"]?.jsonPrimitive?.content == "partial answer" }

            assertFalse("phase" in partial, "Codex preserves phase-less continuation bytes")
            assertEquals("commentary", added.last()["phase"]?.jsonPrimitive?.content)
        } finally {
            wired.provider.onHeadStop()
        }
    }

    @Test
    fun `the arm reads the backend's code_mode_only mark each turn, and says once when none is known`(
        @TempDir tmp: Path,
    ) = runTest {
        var roster = emptyList<DiscoveredModel>()
        val logged = mutableListOf<String>()
        val ctx = context(tmp, writeAccounts(tmp), discovered = HeadDiscoveredModels { roster })
        val wired = CodexResponsesArm(
            StatePaths(baseOverride = tmp.resolve("state")).also { Files.createDirectories(it.stateDir) },
            backgroundScope,
            log = { logged += it },
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("test-denied") },
        ).codexOAuthProvider(ctx, "claude-codex")
        val body = AnthropicParse.parseAnthropicBody(TOOL_BODY)

        val before = wired.provider.buildTurn(body, false, "session-1")
        assertFalse(before.requestBody.toString().contains("\"name\":\"exec\""), "no mark known: direct tools")
        assertFalse("max_output_tokens" in before.requestBody, "direct Codex turns retain uncapped generation")
        assertFalse("safety_identifier" in before.requestBody, "direct Codex turns retain their attribution contract")
        assertEquals(1, logged.count { it.contains("[code-mode] no model is marked code_mode_only") }, "$logged")
        assertTrue(logged.single { it.contains("[code-mode]") }.startsWith("[codex]"), "$logged")

        roster = listOf(ASTRA_LISTED)
        val after = wired.provider.buildTurn(body, false, "session-1")
        assertTrue(after.requestBody.toString().contains("\"name\":\"exec\""), "the refreshed roster marks it")
        assertEquals(1, logged.count { it.contains("[code-mode]") }, "said once, at start: $logged")
        wired.provider.onHeadStop()
    }

    private fun writeAccounts(tmp: Path): Path {
        val primaryFile = tmp.resolve("auth.json")
        Files.writeString(
            primaryFile,
            """{"tokens":{"access_token":"synthetic-primary","account_id":"primary-id"}}""",
        )
        OAuthAccountFiles().writeLabeled(
            AuthKind.ChatgptOAuth,
            primaryFile,
            "backup",
            Json.parseToJsonElement(
                """{"tokens":{"access_token":"synthetic-backup","account_id":"backup-id"}}""",
            ).jsonObject,
        )
        return primaryFile
    }

    private suspend fun pinAccounts(wired: Wired) {
        assertEquals("https://chatgpt.com/backend-api/codex/responses", wired.provider.upstreamUrl)
        assertEquals(listOf("primary", "backup"), wired.accounts.map(WiredAccount::label))
        assertEquals(wired.accounts.single(WiredAccount::primary).auth, wired.auth)
        val creds = wired.auth.credentials() as Credentials.Bearer
        assertEquals(Credentials.Bearer("synthetic-primary", "primary-id"), creds)
        val headers = wired.provider.extraHeaders(creds)
        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("primary-id", headers["ChatGPT-Account-ID"])
    }

    private fun pinTurn(built: BuiltTurn) {
        assertEquals("session-1", built.extraHeaders["session-id"])
        assertTrue(built.extraHeaders.containsKey("thread-id"))
        assertEquals("model=gpt-6-astra", built.extraHeaders["x-codex-routing-hint"])
        val wire = built.requestBody.toString()
        // Client functions remain in exec's manual; the native wait operation is declared separately.
        val declared = built.requestBody.getValue("input").jsonArray.first().jsonObject
        val tools = ResponsesFunctionNamespace().members(declared.getValue("tools").jsonArray).map { it.jsonObject }
        assertEquals(
            listOf("wait"),
            tools.filter { it["type"]?.jsonPrimitive?.content == "function" }
                .map { it.getValue("name").jsonPrimitive.content },
        )
        assertFalse(wire.contains("\"strict\":true"))
        // f6a29a77f: current codex-rs omits empty lite instructions (codex-api/src/common.rs:286-287).
        assertFalse("instructions" in built.requestBody, "current codex-rs omits the empty field")
        assertFalse("max_output_tokens" in built.requestBody, "Codex retains uncapped generation")
        assertFalse(
            "safety_identifier" in built.requestBody,
            "Codex retains its endpoint-specific attribution contract",
        )
        assertEquals("true", built.requestBody["store"]?.jsonPrimitive?.content)
        assertNotNull(built.roundInterceptor)
        assertTrue(wire.contains("\"name\":\"exec\""))
    }

    private fun context(
        tmp: Path,
        authFile: Path,
        listed: List<DiscoveredModel> = emptyList(),
        discovered: HeadDiscoveredModels = HeadDiscoveredModels { listed },
    ): ProviderBuild {
        val key = "codex"
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        val config = ConfigService(state, envReader = { null })
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
                auth = AuthConfig(kind = AuthKind.ChatgptOAuth.wire, file = authFile.toString()),
                quirks = QuirksConfig(accountIdHeader = true, store = true),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry(id = "gpt-6-astra", contextWindow = 1_000_000)),
                defaultContextWindow = 1_000_000,
            ),
            faultPlan = UpstreamFaultPlan(
                watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
                loginCommand = "claude-codex login",
            ),
            cfg = config.getConfig(key),
            roster = PublishedRoster(discovered = discovered),
        )
    }
}

/** gpt-6-astra as the backend's list carries it: code_mode_only, and use_responses_lite, which decides its shape since
 *  fix 5 (Oct 27) as it decides Codex's (models.json, re-read 2026-09-04). */
private val ASTRA_LISTED = DiscoveredModel(
    "gpt-6-astra",
    runtimeMetadata = ModelRuntimeMetadata(toolMode = "code_mode_only", useResponsesLite = true),
)

private const val TOOL_BODY =
    """{"model":"gpt-6-astra","stream":true,"max_tokens":1024,""" +
        """"metadata":{"user_id":"opaque-source-user"},""" +
        """"tools":[{"name":"Read","input_schema":{"type":"object"}},""" +
        """{"name":"Bash","input_schema":{"type":"object"},"strict":true}],""" +
        """"messages":[{"role":"user","content":"Ping."}]}"""
