// NEW: failover within one provider, driven through a real head (operator ruling, Oct 3, 3:44 PM CT: "when one hits its
// limit the same command moves to the next in the order Accounts sets"). A passthrough head with three synthetic logins
// in a pool, over a local upstream that refuses a login with a native 429 naming its spent plan window. The turn that
// meets the 429 moves the same request to the next free login before the client hears any refusal. Later turns stay
// on the serving login. With every login held and armed, admission answers the pool's own "earliest reset" refusal
// locally (splice-lead, Oct 12).
// No login is retried within a request, and splice adds no retry words.
//
// PR #247 review, Oct 29 (security): the logins such a head holds serve only a caller proven to be splice's, a session
// splice launched (it names the head's turn key in the launch proof header) or one that already forwards a login the
// pool holds (a session launched before the proof existed). Any other caller on this machine, whatever it presents, is
// refused at the head with a 401, and nothing of its turn reaches the vendor.
package splice.head.admission

import com.sun.net.httpserver.HttpServer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.CredentialKey
import splice.core.auth.CredentialVerdict
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.SHARE_NODE_TOKEN
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.headDeps
import splice.head.headStores
import splice.head.perf.PerfStats
import splice.head.quotaFor
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.credentials.AccountMembershipRefresh
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.InflightGate
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val HOUR_S = 3_600L
private const val SESSION = "one-command"
private const val SEED = 20_261_029L
private const val CASES = 120
private const val RACE_CASES = 24
private const val ANSWER_TIMEOUT_MS = 10_000
private const val RETIRED_TURN_KEY = "a-turn-key-a-rotation-retired"

class PoolFailoverTurnTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the same streamed request moves from the plan-limited login to the next free login`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S))
        rig.start()
        try {
            val first = rig.turn()
            assertEquals(HttpStatusCode.OK, first.first, "no native refusal reaches the client while a login is free")
            assertEquals(listOf("one", "two"), rig.requests, "one attempt on each login within the same request")
            assertEquals(rig.bodies[0], rig.bodies[1], "handoff sends the exact prepared request bytes")
            assertEquals("5-hour plan limit reached", rig.switchReason())
            assertEquals(HttpStatusCode.OK, rig.turn().first)
            assertEquals(listOf("one", "two", "two"), rig.requests)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the same buffered request moves to the next free login without a rate limit answer`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S))
        rig.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn(stream = false).first)
            assertEquals(listOf("one", "two"), rig.requests)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a forwarded single login records its stable label and never retries its native refusal`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S), pooled = false)
        rig.start()
        try {
            assertEquals(HttpStatusCode.TooManyRequests, rig.turn().first)
            assertEquals(listOf("one"), rig.requests)
            assertEquals("claude-code", rig.lastAccount())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a client head records acceptance when a pooled login serves its streamed request`() = runBlocking {
        assertClientHeadAccepted(stream = true)
    }

    @Test
    fun `a client head records acceptance when a pooled login serves its buffered request`() = runBlocking {
        assertClientHeadAccepted(stream = false)
    }

    @Test
    fun `an added account's unauthorized answer does not reject the client head login`() = runBlocking<Unit> {
        val auth = ClientAuthProvider("synthetic")
        val rig = FailoverRig(directory, limited = emptyMap(), headAuth = auth, refusedStatus = 401)
        rig.start()
        try {
            // Slice 4: a login splice holds takes the held-login status, 403 permission_error. 401 stays the
            // client's own forwarded login, so the status itself no longer says the caller's sign-in failed.
            assertEquals(HttpStatusCode.Forbidden, rig.turn(stream = false).first)
            assertInstanceOf(CredentialVerdict.Unverified::class.java, auth.describe().verdict)
        } finally {
            rig.close()
        }
    }

    // LAN share, PR 423 review: another machine's turn reaches a Claude head holding that machine's word, which opens
    // nothing, and the head's pool runs it on a login the head holds.
    @Test
    fun `a node's word runs on a login the pool holds, never on the word`() = runBlocking {
        val own = ClientAuthProvider("own")
        val rig = FailoverRig(directory, limited = emptyMap(), headAuth = own, bearer = SHARE_NODE_TOKEN)
        rig.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn().first)
            assertEquals(listOf("one"), rig.requests, "the pool's login rides in the word's place")
        } finally {
            rig.close()
        }
    }

    // When the login the turn would run on is the caller's own (the head's own logins gone between the share's offer
    // and this turn), there is none to put in the word's place. The word must never reach the vendor, whose 401 would
    // mark the head's own sign-in rejected for every session.
    @Test
    fun `a node's word on a head with no login of its own is answered here, never sent upstream`() = runBlocking {
        val own = ClientAuthProvider("own")
        val rig =
            FailoverRig(directory, limited = emptyMap(), pooled = false, headAuth = own, bearer = SHARE_NODE_TOKEN)
        rig.start()
        try {
            val response = rig.response()
            val body = response.bodyAsText()
            // Slice 4: the P family's one non-login status, from the record's rows (final-design.md section 11).
            // The 503 this carried is a row 2.1.310 also does not re-send, but splice answers permanent endings
            // with one status, and a 503 reads as the machine being down rather than this turn being refused.
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertTrue("another machine" in body, "the answer says why the turn did not run: $body")
            // PR 423 review round 2: a launch plants CLAUDE_CODE_RETRY_WATCHDOG, which retries on its own; only the
            // server can bring a login back, so the node is told not to retry and shows the sentence at once.
            assertEquals("false", response.headers["x-should-retry"], "the node does not retry into it")
            assertTrue(rig.requests.isEmpty(), "the node's word must never reach the vendor: ${rig.requests}")
            assertTrue(own.describe().present, "the head's own sign-in is not marked rejected")
        } finally {
            rig.close()
        }
    }

    // PR 423 review round 3: the held login is refused partway through the turn and the pool, left with no login of
    // its own, falls back to the head's own sign-in, which forwards the caller's credential. The turn must not move
    // onto it: the word would ride to the vendor and its 401 would mark the head's own sign-in rejected.
    @Test
    fun `a node's turn never moves onto the head's own sign-in when its held login is refused`() = runBlocking {
        val own = ClientAuthProvider("own")
        val rig = FailoverRig(
            directory,
            limited = mapOf("one" to 2 * HOUR_S),
            headAuth = own,
            bearer = SHARE_NODE_TOKEN,
            fallsBackTo = own,
        )
        rig.start()
        try {
            val answer = rig.turn(stream = false)
            assertEquals(HttpStatusCode.TooManyRequests, answer.first, "the held login's reset is handed to the client")
            assertEquals(listOf("one"), rig.requests, "the node's word must never reach the vendor")
            assertTrue(own.describe().present, "the head's own sign-in is not marked rejected")
        } finally {
            rig.close()
        }
    }

    // PR #247 review, Oct 29: another account on this machine posting to the head's loopback port with any bearer of
    // its own ran turns on the operator's held logins. Over generated credentials, in either carrier, with no proof or
    // a proof that is not the head's turn key, the head answers 401 and the vendor hears nothing.
    @Test
    fun `a caller that proves nothing gets no turn on a held login, whatever it presents`() = runBlocking {
        val rig = FailoverRig(directory, limited = emptyMap(), headAuth = ClientAuthProvider("own"))
        rig.launched = false
        rig.start()
        try {
            val random = Random(SEED)
            val refused = (1..CASES).mapNotNull { case ->
                val credential = generatedCredential(random)
                val carrier = if (random.nextBoolean()) {
                    "Authorization" to "Bearer $credential"
                } else {
                    "x-api-key" to credential
                }
                val proof = when (random.nextInt(3)) {
                    0 -> emptyMap()
                    1 -> mapOf(LAUNCH_PROOF_HEADER to generatedCredential(random))
                    else -> mapOf(LAUNCH_PROOF_HEADER to "test-operator-token")
                }
                val status = rig.post(mapOf(carrier) + proof).status
                "$carrier $proof -> $status".takeUnless { status == HttpStatusCode.Unauthorized }
            }
            assertEquals(emptyList<String>(), refused, "each of these was served")
            assertEquals(emptyList<String>(), rig.requests, "nothing of a refused turn reaches the vendor")
        } finally {
            rig.close()
        }
    }

    // Oct 29, 21:30 CT: a hookd seat started before the proof existed, on a login token of its own, stopped on
    // "This session's splice key is not valid", a key it never sent. The refused session is told why it got no turn and
    // how to come back on this head's command, never that a splice key of its was wrong.
    @Test
    fun `a session refused for proving nothing reads why, and that the head's command brings it back`() = runBlocking {
        val rig = FailoverRig(directory, limited = emptyMap(), headAuth = ClientAuthProvider("own"))
        rig.launched = false
        rig.start()
        try {
            val refused = rig.post(mapOf("Authorization" to "Bearer sk-ant-oat01-a-token-of-its-own"))
            val body = refused.bodyAsText()
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertTrue("serves the logins it holds only to sessions splice started" in body, body)
            assertTrue("Start this session again with the head's own command" in body, body)
            assertFalse("splice key is not valid" in body, body)
        } finally {
            rig.close()
        }
    }

    // PR #247 review round 3, Oct 30: `splice rotate-key` retires the turn key a launch wrote into the session's proof,
    // and nothing refreshes it there. Splice started that session, so it is never told otherwise: it reads that its
    // splice key is not valid and to start again from splice, which `splice sessions relaunch` does, and nothing of
    // its turn reaches the vendor.
    @Test
    fun `a launched session whose proof a rotation retired reads that its key went stale`() = runBlocking {
        val rig = FailoverRig(directory, limited = emptyMap(), headAuth = ClientAuthProvider("own"))
        rig.launched = false
        rig.start()
        try {
            val refused = rig.post(
                mapOf(
                    "Authorization" to "Bearer sk-ant-oat01-a-token-of-its-own",
                    LAUNCH_PROOF_HEADER to RETIRED_TURN_KEY,
                ),
            )
            val body = refused.bodyAsText()
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertTrue("splice key is not valid" in body, body)
            assertTrue("Start the session again from splice" in body, body)
            assertFalse("started another way" in body, body)
            assertEquals(emptyList<String>(), rig.requests, "nothing of a refused turn reaches the vendor")
        } finally {
            rig.close()
        }
    }

    // The other machine's word is no proof either: the LAN share's relay adds the proof itself, and the word alone,
    // posted straight to the head's port, opens nothing.
    @Test
    fun `the node's word alone, without the relay's proof, gets no turn on a held login`() = runBlocking {
        val rig = FailoverRig(
            directory,
            limited = emptyMap(),
            headAuth = ClientAuthProvider("own"),
            bearer = SHARE_NODE_TOKEN,
        )
        rig.launched = false
        rig.start()
        try {
            assertEquals(HttpStatusCode.Unauthorized, rig.turn().first)
            assertEquals(emptyList<String>(), rig.requests)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a session splice launched runs on a held login whatever its own login is`() = runBlocking {
        val rig = FailoverRig(directory, limited = emptyMap(), headAuth = ClientAuthProvider("own"), bearer = "gone")
        rig.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn().first)
            assertEquals(listOf("one"), rig.requests, "the pool's login rides in place of the session's own")
        } finally {
            rig.close()
        }
    }

    // A session launched before the proof existed holds no proof but forwards the login it signed in with, which is
    // one the pool reads from the same file: it keeps working after the install, without a relaunch.
    @Test
    fun `a session that forwards a login the pool holds runs without the proof`() = runBlocking {
        val rig = FailoverRig(
            directory,
            limited = emptyMap(),
            headAuth = ClientAuthProvider("own"),
            bearer = "two",
        )
        rig.launched = false
        rig.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn().first)
            assertEquals(listOf("two"), rig.requests, "it runs on the login it forwards, which the pool holds")
        } finally {
            rig.close()
        }
    }

    // PR #247 review round 2, Oct 30 (security): the door reads the pool when a turn's headers arrive, and the login is
    // chosen once its body is read. A caller that proves nothing, let through while the head held no login of its own,
    // must not get one that joined while its body was still arriving. Over generated callers and both moments a login
    // can join (before the headers, or between the door and the body's end), every caller that proves nothing is
    // answered 401 and nothing reaches the vendor, while a session splice launched, in the same sequence, is served.
    @Test
    fun `a caller that proves nothing gets no held login that joins while its body arrives`() = runBlocking {
        val own = ClientAuthProvider("own")
        val rig = FailoverRig(directory, limited = emptyMap(), headAuth = own)
        rig.launched = false
        rig.start()
        try {
            val random = Random(SEED)
            val wrong = (1..RACE_CASES).mapNotNull { case ->
                rig.alone(own)
                val carrier = generatedCarrier(random)
                val proven = random.nextInt(4) == 0
                val proof = if (proven) mapOf(LAUNCH_PROOF_HEADER to "test-inference-token") else emptyMap()
                val early = random.nextBoolean()
                if (early) rig.join()
                val before = rig.requests.size
                val rows = rig.perfRows()
                val status = rig.postSplit(mapOf(carrier) + proof) { if (!early) rig.join() }
                val reached = rig.requests.drop(before)
                // a caller refused after its body was read is refused by admission, which records the refusal's row;
                // the door, at the headers, records none
                val byAdmission = rig.perfRows() > rows
                "case $case $carrier proven=$proven early=$early -> $status, vendor $reached, admission $byAdmission"
                    .takeUnless { raceHeld(proven, early, status, reached, byAdmission) }
            }
            assertEquals(emptyList<String>(), wrong)
        } finally {
            rig.close()
        }
    }

    // A head that holds no login of its own forwards the caller's own as it is: nothing of the operator's is spent.
    @Test
    fun `a head holding no login of its own still forwards any caller's own login`() = runBlocking {
        val rig = FailoverRig(directory, limited = emptyMap(), pooled = false, bearer = "three")
        rig.launched = false
        rig.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn().first)
            assertEquals(listOf("three"), rig.requests)
        } finally {
            rig.close()
        }
    }

    private suspend fun assertClientHeadAccepted(stream: Boolean) {
        val auth = ClientAuthProvider("synthetic")
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S), headAuth = auth)
        rig.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn(stream).first)
            assertEquals(listOf("one", "two"), rig.requests)
            assertInstanceOf(CredentialVerdict.Accepted::class.java, auth.describe().verdict)
            assertEquals(true, rig.answer("one")?.refused, "the named quota refusal belongs to the spent login")
            assertEquals(true, rig.answer("two")?.accepted, "the serving login owns its acceptance")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the usage row names the login that served the same request`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S))
        rig.start()
        try {
            rig.turn()
            assertEquals("two", rig.lastAccount(), "usage is attributed to the serving login, not the refused one")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `with every login held each turn asks the earliest reset login and hands a long reset to the client`() =
        runBlocking {
            val resets = mapOf("one" to 3 * HOUR_S, "two" to HOUR_S, "three" to 2 * HOUR_S)
            val rig = FailoverRig(directory, limited = resets)
            rig.start()
            try {
                val before = System.currentTimeMillis() / 1_000
                assertEquals(HttpStatusCode.TooManyRequests, rig.turn().first)
                val after = System.currentTimeMillis() / 1_000
                assertEquals(
                    listOf("one", "two", "three", "two"),
                    rig.requests,
                    "the turn returns to the earliest reset",
                )

                val held = rig.turn()

                assertEquals(HttpStatusCode.TooManyRequests, held.first)
                // The earliest reset is login two's: an hour from the second it answered during the first turn.
                val lines = (before..after).map {
                    LocalTimeText().at(it + HOUR_S)
                }
                assertTrue(lines.any { it in held.second }, held.second)
                assertEquals(
                    listOf("one", "two", "three", "two", "two"),
                    rig.requests,
                    "admission asks the earliest login",
                )
            } finally {
                rig.close()
            }
        }
}

private fun refusal(login: String): String =
    """{"type":"error","error":{"type":"rate_limit_error","message":"five_hour window rejected for $login"}}"""

/** A synthetic subscription login: its own bearer, answered in Anthropic's unified plan family, which the head's own
 *  reader reads (AnthropicReplies, since failure-handling slice 3). */
private class SyntheticLogin(private val token: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")

    // As a native login does: the key of the credential its file holds, read without a refresh.
    override fun observedCredentialKey(): String? = CredentialKey.fromCredentials(Credentials.Bearer(token))
}

/** A credential no login holds: letters, digits and the punctuation a header value carries, or splice's own word. */
private fun generatedCredential(random: Random): String {
    if (random.nextInt(8) == 0) return SHARE_NODE_TOKEN
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~+/="
    val drawn = (1..random.nextInt(1, 48)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    // The pool's own logins and the head's turn key prove the caller, so they are never drawn.
    return drawn.takeUnless { it in setOf("one", "two", "three", "test-inference-token") } ?: "$drawn-x"
}

/** What the race test expects of one case: a proven caller is served; any other is answered 401 with nothing at the
 *  vendor, by admission when its login joined after the door. */
private fun raceHeld(proven: Boolean, early: Boolean, status: Int, reached: List<String>, byAdmission: Boolean) =
    if (proven) status == 200 else status == 401 && reached.isEmpty() && (early || byAdmission)

/** A credential no login holds, in either carrier a caller can put it in. */
private fun generatedCarrier(random: Random): Pair<String, String> {
    val credential = generatedCredential(random)
    return if (random.nextBoolean()) "Authorization" to "Bearer $credential" else "x-api-key" to credential
}

/** [limited] names each login the upstream refuses and how far out its five-hour reset is. */
private class FailoverRig(
    directory: Path,
    private val limited: Map<String, Long>,
    pooled: Boolean = true,
    headAuth: RefreshableAuthProvider? = null,
    refusedStatus: Int? = null,
    bearer: String = "one",
    private val fallsBackTo: RefreshableAuthProvider? = null,
) {
    /** TRUE for a session splice launched: it names the head's turn key in the launch proof header, as the launch
     *  plants it and the LAN share's relay sends it. Read for each request. */
    @Volatile
    var launched: Boolean = true

    val requests = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()
    private val labels = listOf("one", "two", "three")
    private val logins = labels.associateWith(::SyntheticLogin)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { request ->
            bodies += request.requestBody.use { it.readBytes().decodeToString() }
            val login = request.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
            requests += login
            val resetIn = limited[login]
            val unauthorized = refusedStatus ?: 401.takeIf { login == SHARE_NODE_TOKEN }
            if (resetIn != null) fallsBackTo?.let(::fallBack)
            if (resetIn != null) {
                val reset = (System.currentTimeMillis() / 1_000 + resetIn).toString()
                mapOf(
                    "anthropic-ratelimit-unified-status" to "rejected",
                    "anthropic-ratelimit-unified-representative-claim" to "five_hour",
                    "anthropic-ratelimit-unified-reset" to reset,
                    "anthropic-ratelimit-unified-5h-status" to "rejected",
                    "anthropic-ratelimit-unified-5h-utilization" to "1.0",
                    "anthropic-ratelimit-unified-5h-reset" to reset,
                ).forEach { (name, value) -> request.responseHeaders.add(name, value) }
            }
            val body = when {
                unauthorized != null ->
                    """{"type":"error","error":{"type":"authentication_error","message":"synthetic unauthorized"}}"""
                resetIn != null -> refusal(login)
                else -> SUCCESS_WIRE
            }
            val type = if (unauthorized != null || resetIn != null) "application/json" else "text/event-stream"
            request.responseHeaders.add("Content-Type", type)
            val bytes = body.toByteArray(Charsets.UTF_8)
            request.sendResponseHeaders(unauthorized ?: if (resetIn != null) 429 else 200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val held = labels.map { label ->
        PoolAccount(
            label = label,
            primary = label == "one",
            auth = logins.getValue(label),
            quota = AccountQuotaSource { null },
            cooldown = RateLimitCooldown(
                ElapsedClock { 0L },
                store = FileProviderHoldStore(directory.resolve("$label-provider-hold.json"), LogSink {}),
            ),
        )
    }
    private val pool = AccountPool(held, WallClock { System.currentTimeMillis() }).also { it.order = labels }

    /** Opened each time the pool's membership is read, which the head's door does when a turn's headers arrive. */
    @Volatile
    private var poolRead = CountDownLatch(1)

    /** Run by the next read of the pool's membership, on the head's own thread and before that read returns. The pool
     *  calls its refresh hook AHEAD of the read it reconciles, so the door's signal comes a moment before the door has
     *  read: a join the test ran on its own thread on that signal could land first, and the door then refused at the
     *  headers what admission was to refuse (full gate, Oct 30: the captured first reader was the door itself). Armed
     *  once the door has signalled, the join lands on the head's next read, after the door's and before the login is
     *  chosen. */
    private val atNextRead = AtomicReference<(() -> Unit)?>(null)

    init {
        pool.membershipRefresh = AccountMembershipRefresh {
            atNextRead.getAndSet(null)?.invoke()
            poolRead.countDown()
        }
    }
    private val providerClient = HttpClient(CIO)
    private val perfFile = directory.resolve("perf.jsonl")
    private val head = HeadServer(
        PassthroughProvider(
            ProviderTuning(
                name = ProviderName(key = "synthetic", label = "synthetic"),
                catalog = ModelCatalog(
                    discoveryPrefix = "synthetic--",
                    models = listOf(ModelEntry("model", "Synthetic", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "model",
                auth = headAuth ?: if (pooled) logins.getValue("one") else ClientAuthProvider("synthetic"),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${server.address.port}"),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "synthetic"),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = directory,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, client = providerClient),
            gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
        ).copy(
            quotaBundle = quotaFor(null, if (pooled) pool else null),
            policy = HeadDeps.HeadPolicy(forwardClientAuth = true),
            tokens = HeadDeps.HeadTokens({ "test-inference-token" }, { "test-operator-token" }) {
                it == RETIRED_TURN_KEY
            },
        ).copy(stores = headStores(directory).copy(perfStats = PerfStats(perfFile))),
    )
    private val client = HttpClient(CIO) {
        defaultRequest {
            bearerAuth(bearer)
            if (launched) header(LAUNCH_PROOF_HEADER, "test-inference-token")
        }
    }
    private val bare = HttpClient(CIO)

    suspend fun start() = head.start()

    suspend fun turn(stream: Boolean = true): Pair<HttpStatusCode, String> {
        val response = response(stream)
        return response.status to response.bodyAsText()
    }

    /** One turn's whole answer, headers included. */
    suspend fun response(stream: Boolean = true): HttpResponse =
        client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody(
                """{"model":"synthetic--model","stream":$stream,"max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"go"}]}""",
            )
        }

    /** One streamed turn carrying exactly [headers]: no bearer and no proof but theirs. */
    suspend fun post(headers: Map<String, String>): HttpResponse =
        bare.post("http://127.0.0.1:${head.port}/v1/messages") {
            headers.forEach { (name, value) -> header(name, value) }
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody(
                """{"model":"synthetic--model","stream":true,"max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"go"}]}""",
            )
        }

    /** The head holding no login of its own, as [fallBack] leaves it. */
    fun alone(own: RefreshableAuthProvider) = fallBack(own)

    /** The head's three logins joined, as a sign-in in Accounts publishes them. */
    fun join() {
        pool.members = held
        pool.order = labels
    }

    /** One streamed turn carrying exactly [headers], sent by hand: the headers and half the body, then, once the
     *  head's door has read its pool, the rest of the body, with [between] run at the head's next read of its pool
     *  ([atNextRead]). Answers the response's status, after reading the answer to the close, so that no read of this
     *  turn's reaches the next one. */
    suspend fun postSplit(headers: Map<String, String>, between: () -> Unit): Int = withContext(Dispatchers.IO) {
        val body = (
            """{"model":"synthetic--model","stream":true,"max_tokens":16,""" +
                """"messages":[{"role":"user","content":"go"}]}"""
            ).toByteArray(Charsets.UTF_8)
        val lines = mapOf(
            "Host" to "127.0.0.1:${head.port}",
            "Content-Type" to "application/json",
            "Content-Length" to body.size.toString(),
            "Connection" to "close",
            "x-claude-code-session-id" to SESSION,
        ) + headers
        val fields = lines.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        val start = "POST /v1/messages HTTP/1.1\r\n$fields\r\n"
        Socket("127.0.0.1", head.port).use { socket ->
            poolRead = CountDownLatch(1)
            val out = socket.getOutputStream()
            out.write(start.toByteArray(Charsets.UTF_8))
            out.write(body, 0, body.size / 2)
            out.flush()
            check(poolRead.await(10, TimeUnit.SECONDS)) { "the head never read its pool" }
            atNextRead.set(between)
            try {
                out.write(body, body.size / 2, body.size - body.size / 2)
                out.flush()
            } catch (expected: SocketException) {
                // The door answered at the headers and closed before the body's end, so the rest had nowhere to go.
            }
            socket.soTimeout = ANSWER_TIMEOUT_MS
            val answer = socket.getInputStream().bufferedReader()
            val status = answer.readLine().orEmpty()
            try {
                answer.forEachLine { } // to the close: the turn is over before the next case begins
            } catch (expected: SocketException) {
                // A head that closed with the body's rest unread resets the connection after its answer.
            }
            // A turn the door refused reads the pool no more, and its [between] must not run at the next turn's door.
            atNextRead.set(null)
            status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    /** The pool left with no login of its own: only the head's own sign-in, as the membership publishes it. */
    private fun fallBack(own: RefreshableAuthProvider) {
        pool.members = listOf(
            PoolAccount("claude-code", true, own, AccountQuotaSource { null }, RateLimitCooldown(ElapsedClock { 0L })),
        )
        pool.order = listOf("claude-code")
    }

    fun switchReason(): String? = pool.view(SESSION).lastSwitch?.reason

    fun answer(account: String): splice.core.head.ProviderAnswer? = head.providerAnswer(account)

    /** How many perf rows the head has written so far. */
    fun perfRows(): Int {
        check(AsyncFileIo.drain())
        return if (Files.exists(perfFile)) Files.readAllLines(perfFile).size else 0
    }

    fun lastAccount(): String? {
        check(AsyncFileIo.drain())
        val row = Files.readAllLines(perfFile).last()
        return Json.parseToJsonElement(row).jsonObject["account"]?.jsonPrimitive?.content
    }

    suspend fun close() {
        head.stop()
        client.close()
        bare.close()
        providerClient.close()
        server.stop(0)
    }
}

private val SUCCESS_WIRE = listOf(
    """{"type":"message_start","message":{"id":"msg_synthetic","type":"message",""" +
        """"role":"assistant","model":"model","usage":{"input_tokens":1,"output_tokens":0}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"synthetic success"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    """{"type":"message_stop"}""",
).joinToString("\n\n", postfix = "\n\n") { event ->
    val kind = event.substringAfter("\"type\":\"").substringBefore('"')
    "event: $kind\ndata: $event"
}
