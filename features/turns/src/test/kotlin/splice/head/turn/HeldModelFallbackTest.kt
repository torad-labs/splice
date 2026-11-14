// NEW (Nov 13, 2026): a session's fallback model, through the real turn preparation over HTTP. That evening every Fable
// login on one machine ran out for the week and every turn waited until Sunday. A session that named a fallback goes
// upstream on it while every login is held on Fable and one can serve the fallback; it asks Fable again the first turn
// a login can serve it; and a session that named none still asks Fable, as before.
package splice.head.turn

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.ForeignHostLog
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.HeadVoice
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaSnapshot
import splice.core.util.WallClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.AnthropicBodyParse
import splice.head.ClientAuth
import splice.head.RequestBodyReader
import splice.head.admission.AdmissionResponses
import splice.head.headDeps
import splice.head.quotaFor
import splice.head.turn.fallback.SessionFallbacks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val FABLE = "claude-fable-5-2"
private const val OPUS = "claude-opus-5-5"
private const val SESSION = "a-session"
private const val WEEK_SECONDS = 5 * 86_400L

private class FallbackLogin(private val token: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token, token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

class HeldModelFallbackTest {
    private val cooldowns = listOf("first", "second").associateWith { RateLimitCooldown(ProcessElapsedNow()) }
    private val pool = AccountPool(
        cooldowns.map { (label, cooldown) ->
            PoolAccount(
                label = label,
                primary = label == "first",
                auth = FallbackLogin(label),
                quota = object : AccountQuotaSource {
                    override fun snapshot(): QuotaSnapshot? = null
                    override val held: Boolean get() = false
                },
                cooldown = cooldown,
            )
        },
        WallClock(System::currentTimeMillis),
    )

    /** The provider refuses Fable on [label] for the week, as it did that evening. */
    private fun fableSpent(label: String) = cooldowns.getValue(label).planHold.hold(
        PlanLimit("seven_day_overage_included", System.currentTimeMillis() / 1_000 + WEEK_SECONDS, model = FABLE),
    ) {}

    @Test
    fun `a session that named a fallback goes upstream on it while every login is held on Fable`(@TempDir tmp: Path) {
        fableSpent("first")
        fableSpent("second")
        assertEquals(OPUS, sentModel(tmp, mapOf(SESSION to OPUS)))
    }

    @Test
    fun `a session that named no fallback still asks for Fable`(@TempDir tmp: Path) {
        fableSpent("first")
        fableSpent("second")
        assertEquals(FABLE, sentModel(tmp, emptyMap()))
    }

    @Test
    fun `a session goes back to Fable while one login can serve it`(@TempDir tmp: Path) {
        fableSpent("first")
        assertEquals(FABLE, sentModel(tmp, mapOf(SESSION to OPUS)))
    }

    /** The model the prepared turn would ask its upstream for, with [fallbacks] as the sessions' settings. */
    private fun sentModel(tmp: Path, fallbacks: Map<String, String>): String {
        val deps = headDeps(tmp).copy(
            quotaBundle = quotaFor(null, pool).copy(sessionFallbacks = SessionFallbacks(fallbacks::get)),
        )
        val preparation = TurnPreparation(
            provider(),
            deps,
            RequestBodyReader(deps.policy.requestReadTimeoutMs),
            AnthropicBodyParse(),
            ClientAuth(
                deps,
                AdmissionResponses(HeadVoice("claude", loginCommand = null)),
                ForeignHostLog("the test head", deps.log),
            ),
        )
        var sent: String? = null
        testApplication {
            application {
                routing {
                    post("/v1/messages") {
                        val prepared = when (val m = preparation.prepareTurn(call, TurnPerf())) {
                            is Materialized.Done -> m.value
                            is Materialized.TooLarge -> error("the fixture body is within the cap")
                        }
                        val ready = prepared as? Preparation.Ready ?: error("expected Ready, got $prepared")
                        sent = ready.built.requestBody.getValue("model").jsonPrimitive.content
                        call.respondText("ok")
                    }
                }
            }
            client.post("/v1/messages") {
                header(HttpHeaders.ContentType, "application/json")
                header("x-claude-code-session-id", SESSION)
                setBody(
                    """{"model":"$FABLE","stream":false,"max_tokens":16,""" +
                        """"messages":[{"role":"user","content":"hello"}]}""",
                )
            }
        }
        return requireNotNull(sent)
    }

    private fun provider() = PassthroughProvider(
        ProviderTuning(
            name = ProviderName(key = "claude", label = "claude"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude--",
                models = listOf(
                    ModelEntry(FABLE, "Fable", contextWindow = 1_000_000),
                    ModelEntry(OPUS, "Opus", contextWindow = 1_000_000),
                ),
                defaultContextWindow = 1_000_000,
            ),
            pinnedModel = FABLE,
            auth = FallbackLogin("head"),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1"),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "test-passthrough"),
    )
}
