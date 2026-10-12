// NEW: a selected login that becomes held before admission cannot hide a free pooled login.
package splice.head.admission

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.RateLimitReply
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.compaction.CompactionReplay
import splice.head.headDeps
import splice.head.quotaFor
import splice.head.turn.Preparation
import splice.head.turn.TurnDriver
import splice.upstream.BuiltTurn
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.RetryNotice
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.InflightGate
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private const val NANOS_PER_MS = 1_000_000L

class CredentialHoldAdmissionPoolTest {
    @Test
    fun `a hold armed after selection admits the next free login without a client refusal`(@TempDir dir: Path) =
        testApplication {
            val fixture = Fixture(dir)
            application { routing { post("/admission") { fixture.respond(call) } } }
            try {
                val answer = client.post("/admission")
                assertEquals(HttpStatusCode.OK, answer.status, "a free login is an admission, not a native HTTP 429")
                assertEquals("admitted", answer.bodyAsText())
                assertEquals("two", fixture.pool.view("synthetic-command").selectedLabel)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `naming a locally refused selected account never reacquires or refreshes its credential`(@TempDir dir: Path) =
        testApplication {
            val fixture = Fixture(dir)
            fixture.holdAll()
            application { routing { post("/admission") { fixture.respond(call) } } }
            try {
                val before = fixture.credentialReads.get()
                assertEquals(HttpStatusCode.TooManyRequests, client.post("/admission").status)
                assertEquals(before, fixture.credentialReads.get(), "metadata must not refresh a held account")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `a short hold with no spent plan window is waited out and the turn is admitted, never refused`(
        @TempDir dir: Path,
    ) = testApplication {
        val fixture = Fixture(dir, armedMs = 2_000L, relayed = false)
        fixture.holdAll(2_000L)
        application { routing { post("/admission") { fixture.respond(call) } } }
        try {
            val started = System.nanoTime()
            val answer = client.post("/admission")
            val waitedMs = (System.nanoTime() - started) / NANOS_PER_MS
            assertEquals(HttpStatusCode.OK, answer.status, "a hold of seconds is waited out, not a client 429")
            assertTrue(waitedMs >= 1_500L, "the turn waited for the hold to clear, took ${waitedMs}ms")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a spent plan window is still refused at once even when the gateway hold is short`(@TempDir dir: Path) =
        testApplication {
            val fixture = Fixture(dir, armedMs = 2_000L, relayed = false)
            fixture.holdAll(2_000L)
            fixture.spendPlanWindow()
            application { routing { post("/admission") { fixture.respond(call) } } }
            try {
                val started = System.nanoTime()
                assertEquals(HttpStatusCode.TooManyRequests, client.post("/admission").status)
                assertTrue((System.nanoTime() - started) / NANOS_PER_MS < 1_500L, "a spent window is not waited out")
            } finally {
                fixture.close()
            }
        }

    private class Fixture(dir: Path, armedMs: Long = 7_200_000L, private val relayed: Boolean = true) {
        val credentialReads = AtomicInteger()
        private val logins = listOf("one", "two").map { label ->
            PoolAccount(
                label,
                label == "one",
                SyntheticAdmissionAuth(label, credentialReads),
                AccountQuotaSource { null },
                RateLimitCooldown(ElapsedClock { System.nanoTime() / NANOS_PER_MS }),
            )
        }
        val pool = AccountPool(logins, WallClock(System::currentTimeMillis)).also {
            it.order = listOf("one", "two")
        }
        private val selected = (pool.select("synthetic-command") as Selection.Chosen).account

        init {
            val held = logins.first().cooldown
            held.rateLimitReply = RateLimitReply(
                """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic hold"}}""",
                emptyMap(),
            ).takeIf { relayed }
            held.arm(armedMs)
        }

        private val provider = PassthroughProvider(
            ProviderTuning(
                name = ProviderName(key = "synthetic", label = "synthetic"),
                catalog = ModelCatalog(
                    discoveryPrefix = "synthetic--",
                    models = listOf(ModelEntry("model", "Synthetic", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "model",
                auth = logins.first().auth,
                locations = ProviderLocations(baseUrl = "http://127.0.0.1"),
                watchdog = WatchdogBudget(5.seconds, 5.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "synthetic"),
        )
        private val providerClient = HttpClient(CIO)
        private val deps = headDeps(
            dir,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 0, client = providerClient),
        ).copy(quotaBundle = quotaFor(null, pool))
        private val holds = CredentialHoldAdmission(
            provider,
            deps,
            AdmissionResponses(),
            TurnDriver(provider, deps, CompactionReplay()),
            WallClock(System::currentTimeMillis),
            KeyAccount(provider.auth, deps.quotaBundle),
        )

        suspend fun respond(call: ApplicationCall) {
            val slot = (deps.traffic.gate.acquire() as InflightGate.Admission.Acquired).slot
            val admitted = AdmittedTurn(slot, 0L, TurnPerf(clock = ElapsedClock { 0L }))
            val meta = TurnMeta(
                false,
                reasoning = TurnReasoning(
                    showReasoning = ReasoningDisplay.OFF,
                    effort = "high",
                    summary = null,
                    budgetTokens = null,
                ),
                route = TurnRoute(
                    stream = false,
                    originalModel = "model",
                    upstreamModel = "model",
                    clientMaxTokens = 100,
                ),
            ).run { copy(scope = scope.copy(sessionId = "synthetic-command")) }
            val ready = Preparation.Ready(
                BuiltTurn(JsonObject(emptyMap()), meta),
                stream = false,
                inbound = null,
                messagesHash = null,
                hasPriorExchange = false,
            )
            try {
                when (val answer = holds.admit(call, ready, admitted, null, selected)) {
                    is CredentialHoldAdmission.Outcome.Allowed -> {
                        answer.account?.releaseCredentialProbe()
                        call.respondText("admitted")
                    }
                    CredentialHoldAdmission.Outcome.Refused -> Unit
                }
            } finally {
                admitted.close()
            }
        }

        fun holdAll(forMs: Long = 7_200_000L) {
            logins.forEach {
                if (relayed) it.cooldown.rateLimitReply = logins.first().cooldown.rateLimitReply
                it.cooldown.arm(forMs)
            }
        }

        fun spendPlanWindow() {
            val resetAt = System.currentTimeMillis() / 1_000L + 3_600L
            logins.forEach { it.cooldown.planHold.hold(PlanLimit("seven_day", resetAt), RetryNotice { }) }
        }

        fun close() = providerClient.close()
    }

    private class SyntheticAdmissionAuth(
        private val label: String,
        private val reads: AtomicInteger,
    ) : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials {
            reads.incrementAndGet()
            return Credentials.Bearer(label)
        }
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
    }
}
