// NEW: Oct 26, 2026. After that day's key rotation a session was refused at a gateway head for 19 minutes while
// daemon.log stayed quiet, and its silence was read as nothing failing: ClientAuth refused the session's local key and
// wrote nothing.
//
// THE GOAL these tests are written from. Every local credential a head refuses shows in the head's log: one line that
// names the head, the session (its x-claude-code-session-id, through LogSafe) and why it was refused. A key a rotation
// retired is fixed by `splice sessions relaunch`; the other reasons are no credential, and a credential that isn't one
// of splice's. No key is ever printed. A refused caller controls its headers and can loop, so the lines are bounded the
// way ForeignHostLog bounds foreign hosts: a session gets one line when it is first refused and another at most every
// ten minutes while it keeps being refused, and past a limit of named sessions the refusals are counted, not named.
//
// Each test drives a real head over HTTP as Claude Code calls it, on a clock the test moves (HeadSeams.clock), and
// reads the lines the head wrote. The last one states the bound as a property and checks it over generated runs.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.client.SESSION_HEADER
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** The head's key: `splice logs --head` and the console's per-head log find a head's lines by `[gateway]`. */
private const val HEAD = "gateway"

/** The keys in force: the turn key a launched session holds, and the management key. */
private const val TURN_KEY = "turn-key-in-force"
private const val MGMT_KEY = "management-key-in-force"

/** The turn key `splice rotate-key` retired, which a session launched before the rotation still sends. */
private const val RETIRED_KEY = "turn-key-the-rotation-retired"

/** A vendor key, as a session that set ANTHROPIC_API_KEY sends it in x-api-key: a secret, and none of splice's. */
private const val VENDOR_KEY = "sk-ant-api03-the-persons-own-vendor-key"

/** The session most tests refuse, as Claude Code names it. */
private const val SESSION = "6f1c2a9e-3b7d-4e58-9a41-2c8d5e7f0b13"

private const val CLIENT_AGENT = "claude-cli/2.1.310 (external, cli)"
private const val MODEL_ID = "gateway-model"
private const val MODEL = "claude-$HEAD--$MODEL_ID"
private const val WINDOW_TOKENS = 200_000L

/** A refused turn never reaches the provider, so nothing listens here and nothing dials it. */
private const val NO_PROVIDER = "http://127.0.0.1:9"

private const val TURN_BODY = """{"model":"$MODEL","max_tokens":16,""" +
    """"messages":[{"role":"user","content":"go on"}],"stream":true}"""
private const val COUNT_BODY = """{"model":"$MODEL","messages":[{"role":"user","content":"go on"}]}"""

private const val MINUTE_MS = 60_000L
private const val TEN_MINUTES_MS = 10 * MINUTE_MS

/** How many refused sessions a head names at a time: RefusedKeyLog's limit. */
private const val NAMED_LIMIT = 64

/** Refusals of one session sent at once, as a loop in several of its subagents would. */
private const val TOGETHER = 32

/** A session id its caller stretched far past any Claude Code id. */
private const val STRETCHED = 4_000

// The generated runs: how many, how long, how many sessions beyond the limit they draw from, and how many of those
// come back again and again.
private const val RUNS = 120
private const val STEPS = 600
private const val POOL_BEYOND_LIMIT = 32
private const val REPEATERS = 8

/** What a line says for each reason, in the goal's words. */
private val RETIRED = listOf("a key a rotation retired", "splice sessions relaunch")
private val NO_CREDENTIAL = listOf("no credential")
private val NOT_SPLICES = listOf("not one of splice's")
private val NOT_LAUNCHED = listOf("only to a session splice launched", "launch it with the head's command")
private val REASONS = listOf(RETIRED, NO_CREDENTIAL, NOT_SPLICES, NOT_LAUNCHED)

/** What a count line holds: the number of refusals counted so far. */
private val COUNTED = Regex("counted.*?(\\d+) so far")

class RefusedKeyLogTest {

    // The incident: a session launched before the rotation still holds the turn key it retired.
    @Test
    fun `a retired key is refused, and its line names the head, the session and the relaunch`(@TempDir tmp: Path) =
        withHead(tmp) {
            assertEquals(401, turn(SESSION, bearer(RETIRED_KEY)))
            val line = said(SESSION).single()
            assertTrue("[$HEAD]" in line, line)
            assertSays(RETIRED, line)
            assertNoKeyIn(lines)
        }

    @Test
    fun `a session that presents no credential is refused, and its line says so`(@TempDir tmp: Path) =
        withHead(tmp) {
            assertEquals(401, turn(SESSION, null))
            val line = said(SESSION).single()
            assertTrue("[$HEAD]" in line, line)
            assertSays(NO_CREDENTIAL, line)
        }

    @Test
    fun `a vendor key is refused as none of splice's, and its line never prints it`(@TempDir tmp: Path) =
        withHead(tmp) {
            assertEquals(401, turn(SESSION, apiKey(VENDOR_KEY)))
            val line = said(SESSION).single()
            assertTrue("[$HEAD]" in line, line)
            assertSays(NOT_SPLICES, line)
            assertNoKeyIn(lines)
        }

    @Test
    fun `a repeat within ten minutes adds no line, and one after them says it again`(@TempDir tmp: Path) =
        withHead(tmp) {
            assertEquals(401, turn(SESSION, bearer(RETIRED_KEY)))
            advance(MINUTE_MS)
            assertEquals(401, turn(SESSION, bearer(RETIRED_KEY)))
            advance(TEN_MINUTES_MS - MINUTE_MS - 1)
            assertEquals(401, turn(SESSION, bearer(RETIRED_KEY)))
            assertEquals(1, said(SESSION).size, "a repeat within ten minutes adds no line: $lines")

            advance(1)
            assertEquals(401, turn(SESSION, bearer(RETIRED_KEY)))
            val again = said(SESSION)
            assertEquals(2, again.size, "ten minutes after its line, a session still refused is said again: $lines")
            assertSays(RETIRED, again.last())
        }

    @Test
    fun `sessions past the limit are counted, not named`(@TempDir tmp: Path) = withHead(tmp) {
        val named = sessions(0 until NAMED_LIMIT)
        val past = sessions(NAMED_LIMIT until NAMED_LIMIT + 4)
        (named + past).forEach { assertEquals(401, turn(it, null)) }

        named.forEach { assertEquals(1, said(it).size, "named once: $it") }
        past.forEach { assertEquals(emptyList<String>(), said(it), "past the limit, never named") }
        // Counted the way ForeignHostLog counts: said at the first and at each doubling.
        val counts = lines.mapNotNull { COUNTED.find(it)?.groupValues?.get(1)?.toLong() }
        assertEquals(listOf(1L, 2L, 4L), counts, lines.toString())
    }

    // The limit holds the sessions named at a time. A head that named its limit once and then only counted, for the
    // rest of the daemon's life, would go quiet the way it did on Oct 26.
    @Test
    fun `a session refused after the named ones went quiet for ten minutes is named`(@TempDir tmp: Path) =
        withHead(tmp) {
            sessions(0 until NAMED_LIMIT).forEach { assertEquals(401, turn(it, null)) }
            advance(TEN_MINUTES_MS)
            val late = sessions(NAMED_LIMIT..NAMED_LIMIT).single()
            assertEquals(401, turn(late, null))
            assertSays(NO_CREDENTIAL, said(late).single())
        }

    @Test
    fun `refusals of one session that arrive together write one line`(@TempDir tmp: Path) = withHead(tmp) {
        val statuses = coroutineScope { List(TOGETHER) { async { turn(SESSION, apiKey(VENDOR_KEY)) } }.awaitAll() }
        assertEquals(List(TOGETHER) { 401 }, statuses)
        assertSays(NOT_SPLICES, said(SESSION).single())
    }

    // Claude Code counts tokens through the same door: the keys in force open it, and a refusal there is said too.
    @Test
    fun `count_tokens serves the keys in force without a line, and says a refusal`(@TempDir tmp: Path) =
        withHead(tmp) {
            assertEquals(200, countTokens(SESSION, bearer(TURN_KEY)))
            assertEquals(200, countTokens(SESSION, bearer(MGMT_KEY)))
            assertEquals(emptyList<String>(), said(SESSION), "a served session is not refused")

            assertEquals(401, countTokens(SESSION, bearer(RETIRED_KEY)))
            assertSays(RETIRED, said(SESSION).single())
            assertNoKeyIn(lines)
        }

    // A head that forwards the caller's own login holds none of its own, so a turn with no credential is refused there.
    @Test
    fun `a head that forwards the caller's login says when a session presents no credential`(@TempDir tmp: Path) =
        withHead(tmp, forwarding = true) {
            assertEquals(401, turn(SESSION, null))
            val line = said(SESSION).single()
            assertTrue("[$HEAD]" in line, line)
            assertSays(NO_CREDENTIAL, line)
        }

    @Test
    fun `a refusal that names no session is still said`(@TempDir tmp: Path) = withHead(tmp) {
        assertEquals(401, turn(null, bearer(RETIRED_KEY)))
        assertSays(RETIRED, lines.single { RETIRED.first() in it })
    }

    @Test
    fun `a session id stretched by its caller is cut, so its line stays one bounded line`(@TempDir tmp: Path) =
        withHead(tmp) {
            val stretched = "7".repeat(STRETCHED)
            assertEquals(401, turn(stretched, null))
            val line = lines.single { NO_CREDENTIAL.first() in it }
            assertFalse(stretched in line, "the whole id rode into the line")
            assertTrue(line.length < STRETCHED / 4, "${line.length} chars")
        }

    // The bound, in terms of what the log shows. A refusal of a session said less than ten minutes ago adds nothing.
    // Any other refusal is said, naming the session and why, when fewer than the limit of other sessions were said in
    // the last ten minutes, and is counted when that many were, the count said at the first and at each doubling.
    @Test
    fun `over generated runs, each refusal is said, held back or counted as the bound says`() {
        val seen = RefusalCounts()
        repeat(RUNS) { seed -> RefusalRun(Random(seed.toLong()), seen).run() }
        // The runs reach every case the bound distinguishes, or this test proves less than it says.
        assertTrue(seen.heldBack > 0 && seen.saidAgain > 0 && seen.counted > 0, seen.toString())
    }
}

/** How often the generated runs reached each case. */
private data class RefusalCounts(var heldBack: Int = 0, var saidAgain: Int = 0, var counted: Int = 0)

/** One generated run of refusals against a head's refusal log, checked at each step against the bound. */
private class RefusalRun(private val random: Random, private val seen: RefusalCounts) {
    private val lines = mutableListOf<String>()
    private var now = 0L
    private val log = RefusedKeyLog({ lines += it }, ElapsedClock { now })
    private val pool = sessions(0 until NAMED_LIMIT + POOL_BEYOND_LIMIT)

    /** When each session's last line was said. */
    private val saidAt = HashMap<String, Long>()
    private var counted = 0L

    fun run() = repeat(STEPS) { step() }

    private fun step() {
        now += gap()
        val session = pool[random.nextInt(if (random.nextInt(4) == 0) REPEATERS else pool.size)]
        val why = KeyRefusal.entries[random.nextInt(KeyRefusal.entries.size)]
        val recent = saidAt[session]?.let { now - it < TEN_MINUTES_MS } == true
        val others = saidAt.count { (other, at) -> other != session && now - at < TEN_MINUTES_MS }
        val before = lines.size
        log.refused(session, why)
        val written = lines.drop(before)
        when {
            recent -> {
                assertEquals(emptyList<String>(), written, "$session was said less than ten minutes ago")
                seen.heldBack++
            }
            others < NAMED_LIMIT -> {
                val line = written.single()
                assertTrue(session in line, line)
                assertSays(phrases(why), line)
                if (session in saidAt) seen.saidAgain++
                saidAt[session] = now
            }
            else -> count(session, written)
        }
    }

    private fun count(session: String, written: List<String>) {
        counted++
        seen.counted++
        if (counted and (counted - 1) != 0L) {
            assertEquals(emptyList<String>(), written, "a count that is not a doubling is not said")
            return
        }
        val line = written.single()
        assertEquals(counted.toString(), COUNTED.find(line)?.groupValues?.get(1), line)
        assertFalse(session in line, "a counted session is not named: $line")
    }

    /** Mostly together, sometimes a moment or a minute apart, and now and then just inside, at or past ten
     *  minutes: long stretches fill the limit, and the jumps land on the edges of the window. */
    private fun gap(): Long {
        val roll = random.nextInt(1_000)
        return when {
            roll < 700 -> 0L
            roll < 900 -> 1L
            roll < 990 -> random.nextInt(60_000).toLong()
            roll < 994 -> TEN_MINUTES_MS - 1
            roll < 998 -> TEN_MINUTES_MS
            else -> 2 * TEN_MINUTES_MS
        }
    }

    private fun phrases(why: KeyRefusal): List<String> = when (why) {
        KeyRefusal.RETIRED -> RETIRED
        KeyRefusal.NO_CREDENTIAL -> NO_CREDENTIAL
        KeyRefusal.NOT_SPLICES -> NOT_SPLICES
        KeyRefusal.NOT_LAUNCHED -> NOT_LAUNCHED
    }
}

/** A head as the daemon builds one, on a clock the test moves, writing its lines where the test reads them. A gateway
 *  head holds splice's own credential for its provider and takes the turn key from a session; with [forwarding] the
 *  head holds none and forwards each caller's own login. */
private class RefusalRig(tmp: Path, forwarding: Boolean) {
    val lines = CopyOnWriteArrayList<String>()
    private val now = AtomicLong(MINUTE_MS)
    private val http = HttpClient(CIO)
    private val head = HeadServer(
        provider = provider(forwarding),
        listenPort = 0,
        deps = headDeps(tmp, log = { lines += it }, seams = HeadDeps.HeadSeams(clock = ElapsedClock { now.get() }))
            .copy(
                tokens = HeadDeps.HeadTokens(
                    turnKey = { TURN_KEY },
                    managementKey = { MGMT_KEY },
                    retiredKey = { it == RETIRED_KEY },
                ),
                policy = HeadDeps.HeadPolicy(forwardClientAuth = forwarding),
            ),
    )

    fun start() = runBlocking { head.start() }

    fun stop() {
        try {
            runBlocking { head.stop() }
        } finally {
            http.close()
        }
    }

    /** Moves the head's clock on by [ms]. */
    fun advance(ms: Long) {
        now.addAndGet(ms)
    }

    /** The lines the head wrote that name [session]. */
    fun said(session: String): List<String> = lines.filter { session in it }

    /** A turn as Claude Code posts one, in [session] (no session header when null), presenting [credential] (no
     *  credential header when null). Returns the status the head answered. */
    suspend fun turn(session: String?, credential: Pair<String, String>?): Int =
        post("/v1/messages", TURN_BODY, session, credential)

    suspend fun countTokens(session: String?, credential: Pair<String, String>?): Int =
        post("/v1/messages/count_tokens", COUNT_BODY, session, credential)

    private suspend fun post(path: String, body: String, session: String?, credential: Pair<String, String>?): Int =
        http.post("http://127.0.0.1:${head.port}$path") {
            credential?.let { (name, value) -> header(name, value) }
            header("anthropic-version", "2023-06-01")
            header("User-Agent", CLIENT_AGENT)
            header("x-app", "cli")
            session?.let { header(SESSION_HEADER, it) }
            header("Content-Type", "application/json")
            setBody(body)
        }.status.value
}

/** The credential a gateway head holds for its provider: splice's own, never a session's. */
private class GatewayCredential : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("splice-held-provider-key", "x-api-key", "")

    override suspend fun refresh(): Credentials = credentials()

    override suspend fun describe(): AuthDescription = AuthDescription(true, "held")
}

private fun provider(forwarding: Boolean): PassthroughProvider = PassthroughProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = HEAD, label = HEAD),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-$HEAD--",
            models = listOf(ModelEntry(MODEL_ID, "Gateway Model", contextWindow = WINDOW_TOKENS)),
            defaultContextWindow = WINDOW_TOKENS,
        ),
        pinnedModel = MODEL_ID,
        auth = if (forwarding) ClientAuthProvider(HEAD) else GatewayCredential(),
        locations = ProviderLocations(baseUrl = NO_PROVIDER),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    quirks = PassthroughQuirks(providerTag = HEAD),
)

/** Runs [block] against a started head, and stops the head after it. */
private fun withHead(tmp: Path, forwarding: Boolean = false, block: suspend RefusalRig.() -> Unit) {
    val rig = RefusalRig(tmp, forwarding)
    rig.start()
    try {
        runBlocking { rig.block() }
    } finally {
        rig.stop()
    }
}

private fun bearer(key: String): Pair<String, String> = "Authorization" to "Bearer $key"

private fun apiKey(key: String): Pair<String, String> = "x-api-key" to key

/** Session ids shaped as Claude Code's, one per number in [numbers]. */
private fun sessions(numbers: IntRange): List<String> =
    numbers.map { "0b5e0000-0000-4000-8000-${it.toString().padStart(12, '0')}" }

/** [line] gives [reason], in each of its words, and no other reason. */
private fun assertSays(reason: List<String>, line: String) {
    reason.forEach { assertTrue(it in line, "says '$it': $line") }
    REASONS.filter { it !== reason }.flatten().forEach { assertFalse(it in line, "says only its own reason: $line") }
}

/** No line carries any key, in force, retired or the vendor's. */
private fun assertNoKeyIn(lines: List<String>) {
    listOf(TURN_KEY, MGMT_KEY, RETIRED_KEY, VENDOR_KEY).forEach { key ->
        assertTrue(lines.none { key in it }, "a line printed a key: $lines")
    }
}
