// NEW: V4-134 — a REAL HeadServer serving REAL turns reports each console fact through HeadEvents,
// from the seam that already held it, with the values the rest of the daemon recorded for the same
// turn.
//
// NOT "something was emitted". Every assertion compares the event to the thing it claims to point
// at: turn.end's perfRowId and outcome against the perf row the head actually wrote, turn.start's
// session against the header the client sent, account.switch against the account the pool actually
// moved to. A producer that emitted the wrong row's key, a stale outcome or the wrong account fails
// here, not only one that emitted nothing.
//
// THIS IS ALSO THE PIN for the defaulted seams. HeadSeams.events and TurnTelemetry's events both
// default to NoHeadEvents so the tests that build them directly keep compiling; the production path
// passes the head's own through TurnDriver. Drop that argument and turn.end never arrives here.
//
// V4-130 added three facts observed on the request itself (TurnPreparation): a SendMessage edge, a
// locally answered activity label and a near-miss label query sent upstream. The last test drives all
// three through the same real head, including a retried request that must not report its edges twice.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.WallClock
import splice.head.perf.PerfStats
import splice.head.usage.QuotaTracker
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderTuning
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val SESSION = "session-v4134"
private const val WAIT_MS = 10_000L
private const val POLL_MS = 20L

/** Every call a head made, in order, as one line each — the whole observable surface of the seam. */
private class RecordingEvents : HeadEvents {
    val calls = CopyOnWriteArrayList<String>()

    override fun lifecycle(state: HeadLifecycle) {
        calls.add("lifecycle ${state.wire}")
    }

    override fun turnStarted(session: String?) {
        calls.add("start $session")
    }

    override fun turnEnded(perfRowId: String, outcome: String) {
        calls.add("end $perfRowId $outcome")
    }

    override fun accountSwitched(from: String?, to: String) {
        calls.add("switch $from $to")
    }

    override fun messageSent(session: String, to: String, toolUseId: String) {
        calls.add("edge $session $to $toolUseId")
    }

    override fun activityLabel(session: String?, label: String) {
        calls.add("label $session $label")
    }

    override fun labelQueryUpstream(session: String?) {
        calls.add("upstream $session")
    }

    fun turnCalls(): List<String> = calls.filterNot { it.startsWith("lifecycle") }
}

class HeadEventsTest {

    @Test
    fun `a head reports its lifecycle once per real transition, draining before stopped`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.head.stop()
            // A second stop of a head that is already down is not a transition: nothing to report.
            rig.head.stop()
            assertEquals(
                listOf("lifecycle started", "lifecycle draining", "lifecycle stopped"),
                rig.events.calls.filter { it.startsWith("lifecycle") },
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a served turn is announced with its session and ends with its own perf row's key and outcome`() =
        runBlocking {
            val rig = Rig()
            try {
                rig.start()
                rig.turn()
                val row = rig.perfRows(1).single()
                assertEquals(
                    listOf("start $SESSION", "end ${row.first} ${row.second}"),
                    rig.events.turnCalls(),
                    "turn.start must carry the client's session and turn.end the row the head wrote",
                )
            } finally {
                rig.close()
            }
        }

    @Test
    fun `a switch to another account is reported with the accounts the pool actually used`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.turn()
            rig.exhaustPrimary()
            rig.turn()
            val rows = rig.perfRows(2)
            assertEquals(
                listOf(
                    "start $SESSION",
                    "end ${rows[0].first} ${rows[0].second}",
                    "start $SESSION",
                    "switch primary backup",
                    "end ${rows[1].first} ${rows[1].second}",
                ),
                rig.events.turnCalls(),
            )
            assertEquals("backup", rig.selectedAccount(), "the switch event must name the account now selected")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a turn refused locally still starts and ends, keyed by its refusal row`() = runBlocking {
        val rig = Rig()
        try {
            rig.start()
            rig.exhaustAll()
            rig.turn()
            val row = rig.perfRows(1).single()
            assertTrue(row.second.startsWith("error:"), "the rig must reach a LOCAL refusal row, got ${row.second}")
            assertEquals(listOf("start $SESSION", "end ${row.first} ${row.second}"), rig.events.turnCalls())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `edges, a local label and a near-miss label query are reported from the request that carries them`() =
        runBlocking {
            val rig = Rig()
            try {
                rig.start()
                rig.turn(afterSendMessage("carry on"))
                rig.turn(afterSendMessage("carry on")) // a retry: the same calls, already reported
                rig.turn(afterSendMessage("Describe your most recent action in 3-5 words using present tense (-ing)."))
                rig.turn(afterSendMessage("In present tense, describe your MOST RECENT ACTION in a few words."))
                val rows = rig.perfRows(3)
                assertEquals(
                    listOf(
                        "edge $SESSION uds:/run/peer.sock toolu_a",
                        "edge $SESSION builder toolu_b",
                        "start $SESSION",
                        "end ${rows[0].first} ${rows[0].second}",
                        "start $SESSION",
                        "end ${rows[1].first} ${rows[1].second}",
                        "label $SESSION Messaging a peer session",
                        "upstream $SESSION",
                        "start $SESSION",
                        "end ${rows[2].first} ${rows[2].second}",
                    ),
                    rig.events.turnCalls(),
                    "each call reported once; the exact query answered locally with no turn; the near miss served AND counted",
                )
            } finally {
                rig.close()
            }
        }
}

/** V4-130: an assistant turn that messaged two peers, then its tool results, then [lastUser]. */
private fun afterSendMessage(lastUser: String): String =
    """[{"role":"user","content":"coordinate"},""" +
        """{"role":"assistant","content":[{"type":"tool_use","id":"toolu_a","name":"SendMessage",""" +
        """"input":{"to":"uds:/run/peer.sock","message":"never recorded"}},""" +
        """{"type":"tool_use","id":"toolu_b","name":"SendMessage","input":{"to":"builder","message":"x"}}]},""" +
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_a","content":"sent"},""" +
        """{"type":"tool_result","tool_use_id":"toolu_b","content":"sent"},{"type":"text","text":"$lastUser"}]}]"""

/** One codex head on a mock upstream with a two-account pool, reporting to [events]. */
private class Rig {
    val events = RecordingEvents()
    private val tmp: Path = Files.createTempDirectory("v4134-head-events")
    private val mock = MockChatGptUpstream()
    private val port: Int get() = head.port
    private val perfFile = tmp.resolve("perf.jsonl")
    private val primaryQuota = QuotaTracker(tmp.resolve("primary-quota.json"))
    private val backupQuota = QuotaTracker(tmp.resolve("backup-quota.json"))
    private val primaryAuth = RigAuth("primary-token", "primary-id")
    private val pool = AccountPool(
        listOf(
            account("primary", true, primaryAuth, primaryQuota),
            account("backup", false, RigAuth("backup-token", "backup-id"), backupQuota),
        ),
        WallClock(System::currentTimeMillis),
    )
    val head = HeadServer(
        provider = provider(),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(firstByteTimeoutMs = 5_000L, totalTimeoutMs = 30_000L, maxRetries = 2),
            quota = quotaFor(primaryQuota, pool, mapOf("primary" to primaryQuota, "backup" to backupQuota)),
            seams = HeadDeps.HeadSeams(events = events),
        ).copy(stores = headStores(tmp).copy(perfStats = PerfStats(perfFile))),
    )
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }

    init {
        primaryQuota.record(quota(10.0))
        backupQuota.record(quota(37.0))
    }

    suspend fun start() {
        head.start()
        awaitListening(port)
    }

    suspend fun turn(messages: String = """[{"role":"user","content":"go"}]""") {
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:basic",
                    "messages":$messages}""",
            )
        }.bodyAsText()
    }

    fun exhaustPrimary() {
        primaryQuota.record(quota(100.0))
    }

    fun exhaustAll() {
        primaryQuota.record(quota(100.0))
        backupQuota.record(quota(100.0))
    }

    fun selectedAccount(): String? = pool.view(SESSION).selectedLabel

    /** The perf rows the head wrote, as (ts, outcome), once [count] of them have landed. The append is
     *  asynchronous, so this waits for the file rather than reading it once. */
    suspend fun perfRows(count: Int): List<Pair<String, String>> = withTimeout(WAIT_MS) {
        var lines = rowLines()
        while (lines.size < count) {
            delay(POLL_MS)
            lines = rowLines()
        }
        lines.map { line ->
            val row = Json.parseToJsonElement(line).jsonObject
            row.getValue("ts").jsonPrimitive.long.toString() to row.getValue("outcome").jsonPrimitive.content
        }
    }

    private fun rowLines(): List<String> =
        if (Files.exists(perfFile)) Files.readAllLines(perfFile).filter { it.isNotBlank() } else emptyList()

    suspend fun close() {
        head.stop()
        client.close()
        mock.stop()
    }

    private fun account(label: String, primary: Boolean, auth: RefreshableAuthProvider, tracker: QuotaTracker) =
        PoolAccount(
            label = label,
            primary = primary,
            auth = auth,
            quota = AccountQuotaSource(tracker::snapshot),
            cooldown = RateLimitCooldown(ProcessElapsedNow()),
            credentialPresent = true,
        )

    private fun quota(used: Double): QuotaSnapshot {
        val reset = System.currentTimeMillis() / 1_000L + 3_600L
        return QuotaSnapshot(
            fiveHour = QuotaWindow(used, reset, 18_000L),
            sevenDay = QuotaWindow(20.0, reset + 82_800L, 604_800L),
            plan = "plus",
        )
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
            auth = primaryAuth,
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

private class RigAuth(private val token: String, private val accountId: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token, accountId)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}
