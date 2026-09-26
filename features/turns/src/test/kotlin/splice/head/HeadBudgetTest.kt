// NEW: V4-133 review — a REAL HeadServer serving REAL turns consults its budget before each one and
// reports each served turn's spend after its perf row.
//
// The failing state the review named: `PUT /api/budgets {"budgets":[{"head":"h","daily_usd":0.01,
// "action":"block"}]}` answered 200 and turns on `h` kept being served, because nothing on the turn
// path read a budget. The block arm here is what the client sees — a 403 permission_error carrying
// the budget's own sentence, before any upstream call — and the spend arm compares what the budget
// was told against the perf row the head actually wrote for that turn.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.budget.BudgetBlock
import splice.core.budget.HeadBudget
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderTuning
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private const val BUDGET_WAIT_MS = 10_000L
private const val BUDGET_POLL_MS = 20L

/** What a head told its budget, and the one answer it gives every admission. */
private class RecordingBudget(private val answer: BudgetBlock?) : HeadBudget {
    val admits = AtomicInteger()
    val spends = CopyOnWriteArrayList<Triple<Long, String, Map<String, Long>>>()

    override fun admit(): BudgetBlock? {
        admits.incrementAndGet()
        return answer
    }

    override fun spent(atMs: Long, model: String, counters: Map<String, Long>) {
        spends.add(Triple(atMs, model, counters))
    }
}

class HeadBudgetTest {

    @Test
    fun `a reached block budget refuses the turn with its sentence as a 403, before any upstream call`() =
        runBlocking {
            val block = BudgetBlock(
                message = "head 'codex' has spent \$2.00 today (UTC) against its \$2.00 daily budget",
                detail = "spent_usd=2.00 limit_usd=2.00 unpriced_turns=0",
            )
            val rig = BudgetRig(RecordingBudget(block))
            try {
                rig.start()
                val response = rig.turn()
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.Forbidden, response.status, body)
                val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
                assertEquals("permission_error", error.getValue("type").jsonPrimitive.content)
                assertEquals(block.message, error.getValue("message").jsonPrimitive.content)
                assertEquals(0, rig.upstreamCalls(), "a refused turn must never reach the upstream")
                val outcome = rig.perfRows(1).single().getValue("outcome").jsonPrimitive.content
                assertEquals(
                    OutcomeTag.BUDGET_BLOCKED.wire,
                    outcome,
                    "the refusal is on record beside the turns that ran",
                )
                assertEquals(1, rig.budget.admits.get(), "the budget is asked once per turn")
                assertEquals(0, rig.budget.spends.size, "a refused turn spent nothing")
            } finally {
                rig.close()
            }
        }

    @Test
    fun `a served turn reports its spend with its own perf row's ts, model and token counters`() = runBlocking {
        val rig = BudgetRig(RecordingBudget(null))
        try {
            rig.start()
            val response = rig.turn()
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val row = rig.perfRows(1).single()
            val (atMs, model, counters) = rig.budget.spends.single()
            assertEquals(row.getValue("ts").jsonPrimitive.long, atMs)
            assertEquals(row.getValue("model").jsonPrimitive.content, model)
            for (key in listOf(PerfKeys.IN_TOKENS, PerfKeys.OUT_TOKENS)) {
                assertEquals(row.getValue(key).jsonPrimitive.long, counters[key], "$key must be the row's own")
            }
            assertEquals(1, rig.budget.admits.get())
        } finally {
            rig.close()
        }
    }
}

/** One codex head on a mock upstream, weighed against [budget]. */
private class BudgetRig(val budget: RecordingBudget) {
    private val tmp: Path = Files.createTempDirectory("v4133-head-budget")
    private val mock = MockChatGptUpstream()
    private val perfFile = tmp.resolve("perf.jsonl")
    private val auth = BudgetRigAuth()
    private val head = HeadServer(
        provider = provider(),
        listenPort = 0,
        deps = headDeps(tmp = tmp, quota = quotaFor(null, null, budget = budget)),
    )
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }

    suspend fun start() {
        head.start()
        awaitListening(head.port)
    }

    suspend fun turn(): HttpResponse = client.post("http://127.0.0.1:${head.port}/v1/messages") {
        header("Content-Type", "application/json")
        setBody(
            """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                "system":"You are a test. SCENARIO:basic",
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }

    fun upstreamCalls(): Int = mock.upstreamBodies.size

    /** The perf rows the head wrote, once [count] have landed (the append is asynchronous). */
    suspend fun perfRows(count: Int): List<JsonObject> = withTimeout(BUDGET_WAIT_MS) {
        var lines = rowLines()
        while (lines.size < count) {
            delay(BUDGET_POLL_MS)
            lines = rowLines()
        }
        lines.map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun rowLines(): List<String> =
        if (Files.exists(perfFile)) Files.readAllLines(perfFile).filter { it.isNotBlank() } else emptyList()

    suspend fun close() {
        head.stop()
        client.close()
        mock.stop()
    }

    private fun provider(): CodexProvider = CodexProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = auth,
            baseUrl = mock.baseUrl,
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )
}

private class BudgetRigAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("budget-token", "budget-id")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}
