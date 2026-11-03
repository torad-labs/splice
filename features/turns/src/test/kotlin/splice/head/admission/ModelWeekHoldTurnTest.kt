// NEW: a week spent on one model holds a login for that model alone (Marcos, Nov 3, 2026, 1:15 AM CT: "we just hit 5hs
// limit and instead of handing to the next account with available limit, it just got locked waiting for the 5h limit
// to finish"). That night Anthropic refused Fable on accounts 2, 3 and 6 with the claim seven_day_overage_included,
// their week at 65 to 73% and Fable at 100%, and splice held all three for every model until Nov 8. When account 8, the
// one in use, hit its five-hour limit, no login was left for Opus or Sonnet, and the turns waited for its reset.
//
// The goal, as the person running the command sees it: a login whose provider refused one model for the week keeps
// serving every other model, so an Opus turn moves to it the moment the login in use runs out; Fable turns stay off it
// until its reset, whatever else succeeds there; and a restart, the upgrade from a build that held such a login whole
// included, keeps that true. Driven through a real head over HTTP, three logins in a pool, over a local upstream that
// answers each login and model as the provider did that night.
package splice.head.admission

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.headDeps
import splice.head.quotaFor
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.InflightGate
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val FABLE = "claude-fable-5-1"
private const val OPUS = "claude-opus-5-5"
private const val SESSION = "one-command"
private const val HOUR_S = 3_600L
private const val DAY_S = 24 * HOUR_S

/** What the provider answered on login two that night: Fable's week spent, the login's own week not. */
private val FABLE_WEEK = WeekRefusal("seven_day_overage_included", 6 * DAY_S, setOf(FABLE))

/** Login four's own week spent, every model refused (accounts 4, 5 and 7 that night). */
private val WHOLE_WEEK = WeekRefusal("seven_day", 2 * DAY_S, models = null)

/** Login eight's five hours spent, every model refused (account 8 at 1:15 AM CT). */
private val FIVE_HOURS = WeekRefusal("five_hour", 2 * HOUR_S, models = null)

class ModelWeekHoldTurnTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `an Opus turn moves to the login whose only hold is Fable's week when the login in use hits its five hours`() =
        runBlocking {
            val rig = WeekRig(directory, mapOf("two" to FABLE_WEEK, "four" to WHOLE_WEEK))
            rig.start()
            try {
                assertEquals(HttpStatusCode.OK, rig.turn(FABLE).first, "a Fable turn finds the login with room")
                assertEquals(listOf("two" to FABLE, "four" to FABLE, "eight" to FABLE), rig.requests)
                assertEquals(HttpStatusCode.OK, rig.turn(OPUS).first)

                rig.refuse("eight", FIVE_HOURS)
                val moved = rig.turn(OPUS)

                assertEquals(HttpStatusCode.OK, moved.first, moved.second)
                assertEquals(
                    "two" to OPUS,
                    rig.requests.last(),
                    "the login whose only hold is Fable's week serves Opus",
                )
            } finally {
                rig.close()
            }
        }

    @Test
    fun `a Fable turn stays off the login whose Fable week is spent after Opus succeeds there`() = runBlocking {
        val rig = WeekRig(directory, mapOf("two" to FABLE_WEEK, "four" to WHOLE_WEEK))
        rig.start()
        try {
            rig.turn(FABLE)
            rig.refuse("eight", FIVE_HOURS)
            assertEquals(HttpStatusCode.OK, rig.turn(OPUS).first)
            assertEquals(HttpStatusCode.OK, rig.turn(OPUS).first, "Opus keeps its login")
            assertEquals("two" to OPUS, rig.requests.last())

            rig.turn(FABLE)

            assertEquals(1, rig.requests.count { it == "two" to FABLE }, "${rig.requests}")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a restart keeps Fable's week on Fable alone`() = runBlocking {
        val before = WeekRig(directory, mapOf("two" to FABLE_WEEK, "four" to WHOLE_WEEK))
        before.start()
        try {
            assertEquals(HttpStatusCode.OK, before.turn(FABLE).first)
        } finally {
            before.close()
        }

        val after = WeekRig(directory, mapOf("two" to FABLE_WEEK, "four" to WHOLE_WEEK, "eight" to FIVE_HOURS))
        after.start()
        try {
            val opus = after.turn(OPUS)
            assertEquals(HttpStatusCode.OK, opus.first, opus.second)
            assertEquals(
                listOf("two" to OPUS),
                after.requests,
                "the first login asked serves, its Fable week kept apart",
            )

            after.turn(FABLE)

            assertFalse("two" to FABLE in after.requests, "Fable stays off it after the restart: ${after.requests}")
        } finally {
            after.close()
        }
    }

    @Test
    fun `a Fable week saved before splice kept its model holds no other model after the upgrade`() = runBlocking {
        val reset = System.currentTimeMillis() / 1_000 + 6 * DAY_S
        // Account 2's file on Nov 3, 2026, as the build of Oct 30 wrote it: the claim, its reset, and no model.
        Files.writeString(
            directory.resolve("two-provider-hold.json"),
            """{"reset_at_epoch_seconds":$reset,""" +
                """"plan":{"claim":"seven_day_overage_included","reset_at_epoch_seconds":$reset}}""",
        )
        val rig = WeekRig(directory, mapOf("two" to FABLE_WEEK, "four" to WHOLE_WEEK, "eight" to FIVE_HOURS))
        rig.start()
        try {
            val opus = rig.turn(OPUS)

            assertEquals(HttpStatusCode.OK, opus.first, opus.second)
            assertEquals("two" to OPUS, rig.requests.last(), "${rig.requests}")
            assertFalse("two" in rig.blockedUntil(), "the console is not told it is held: ${rig.blockedUntil()}")
        } finally {
            rig.close()
        }
    }
}

/** A provider's refusal of a login: the window [claim] names spent for [resetIn] seconds, for [models], or for every
 *  model when null. */
private data class WeekRefusal(val claim: String, val resetIn: Long, val models: Set<String>?)

private class WeekLogin(private val token: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
    override fun observedCredentialKey(): String? = CredentialKey.fromCredentials(Credentials.Bearer(token))
}

/** Three logins in the order two, four, eight, eight the primary; the upstream refuses each as [refusals] says. */
private class WeekRig(directory: Path, refusals: Map<String, WeekRefusal>) {
    /** Each request the upstream received: the login it came on and the model it asked for. */
    val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val refusals = ConcurrentHashMap(refusals)
    private val labels = listOf("two", "four", "eight")
    private val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { request ->
            val body = request.requestBody.use { it.readBytes().decodeToString() }
            val model = Json.parseToJsonElement(body).jsonObject["model"]?.jsonPrimitive?.content.orEmpty()
            val login = request.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
            requests += login to model
            val refusal = this@WeekRig.refusals[login]?.takeIf { it.models == null || model in it.models }
            if (refusal != null) {
                val reset = (System.currentTimeMillis() / 1_000 + refusal.resetIn).toString()
                mapOf(
                    "anthropic-ratelimit-unified-status" to "rejected",
                    "anthropic-ratelimit-unified-representative-claim" to refusal.claim,
                    "anthropic-ratelimit-unified-reset" to reset,
                    "retry-after" to refusal.resetIn.toString(),
                    "Content-Type" to "application/json",
                ).forEach { (name, value) -> request.responseHeaders.add(name, value) }
                val bytes = (
                    """{"type":"error","error":{"type":"rate_limit_error",""" +
                        """"message":"This request would exceed your account's rate limit. Please try again later."}}"""
                    )
                    .toByteArray()
                request.sendResponseHeaders(429, bytes.size.toLong())
                request.responseBody.use { it.write(bytes) }
            } else {
                request.responseHeaders.add("Content-Type", "text/event-stream")
                val bytes = success(model).toByteArray()
                request.sendResponseHeaders(200, bytes.size.toLong())
                request.responseBody.use { it.write(bytes) }
            }
        }
        start()
    }
    private val pool = AccountPool(
        labels.map { label ->
            PoolAccount(
                label = label,
                primary = label == "eight",
                auth = WeekLogin(label),
                quota = AccountQuotaSource { null },
                cooldown = RateLimitCooldown(
                    ElapsedClock { 0L },
                    store = FileProviderHoldStore(directory.resolve("$label-provider-hold.json"), LogSink {}),
                ),
            )
        },
        WallClock { System.currentTimeMillis() },
    ).also { it.order = labels }
    private val providerClient = HttpClient(CIO)
    private val head = HeadServer(
        PassthroughProvider(
            ProviderTuning(
                name = ProviderName(key = "claude-splice", label = "claude-splice"),
                catalog = ModelCatalog(
                    discoveryPrefix = "synthetic--",
                    models = listOf(
                        ModelEntry(FABLE, "Fable", contextWindow = 200_000),
                        ModelEntry(OPUS, "Opus", contextWindow = 200_000),
                    ),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = OPUS,
                auth = WeekLogin("eight"),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${server.address.port}"),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "claude-splice"),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = directory,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, client = providerClient),
            gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
        ).copy(
            quotaBundle = quotaFor(null, pool),
            policy = HeadDeps.HeadPolicy(forwardClientAuth = true),
            tokens = HeadDeps.HeadTokens({ "test-inference-token" }, { "test-operator-token" }) { false },
        ),
    )
    private val client = HttpClient(CIO) {
        defaultRequest {
            // the session's own login, no login of the pool's, so the pool's order places each turn (the launch proof
            // proves the caller)
            bearerAuth("the-session-own-login")
            header(LAUNCH_PROOF_HEADER, "test-inference-token")
        }
    }

    suspend fun start() = head.start()

    /** The provider starts refusing [login] as [refusal] says, from its next request. */
    fun refuse(login: String, refusal: WeekRefusal) {
        refusals[login] = refusal
    }

    /** One streamed turn of the command's session, asking for [model]: its status and its answer. */
    suspend fun turn(model: String): Pair<HttpStatusCode, String> {
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody("""{"model":"$model","stream":true,"max_tokens":16,"messages":[{"role":"user","content":"go"}]}""")
        }
        return response.status to response.bodyAsText()
    }

    /** The logins the pool tells the console are held, and until when. */
    fun blockedUntil(): Map<String, Long> = pool.view(null).blockedUntilEpochSecondsByLabel

    suspend fun close() {
        head.stop()
        client.close()
        providerClient.close()
        server.stop(0)
    }
}

private fun success(model: String): String = listOf(
    """{"type":"message_start","message":{"id":"msg_synthetic","type":"message",""" +
        """"role":"assistant","model":"$model","usage":{"input_tokens":1,"output_tokens":0}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"synthetic success"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    """{"type":"message_stop"}""",
).joinToString("\n\n", postfix = "\n\n") { event ->
    "event: ${event.substringAfter("\"type\":\"").substringBefore('"')}\ndata: $event"
}
