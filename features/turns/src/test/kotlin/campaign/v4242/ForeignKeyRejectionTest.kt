// NEW: V4-242 (2026-09-26) — a 401 that names a key the account never sent is the upstream's failure.
//
// During the Codex outage of 2026-09-25 the HTTP side answered 401 invalid_api_key naming a masked
// OpenAI service key (`sk-svcac…fvMA`), while the account had sent its own sign-in token. The head
// read every 401 as the user's credential failing: the client was told authentication_error with
// "run: claudex login", and the account was marked unavailable, so the next turn left it. These pin
// both halves against a head with a real two-account pool, and pin the ordinary 401 beside them.
package campaign.v4242

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import splice.head.HeadServer
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.quotaFor
import splice.head.usage.QuotaTracker
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderTuning
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** An upstream that answers every request 401 with [body], and records which credential each carried. */
private class RejectingUpstream(private val body: String) {
    val authorizations = CopyOnWriteArrayList<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { ex ->
            ex.requestBody.use { it.transferTo(OutputStream.nullOutputStream()) }
            authorizations += ex.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(UNAUTHORIZED, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun stop() = server.stop(0)
}

private class SignInToken(private val token: String, private val accountId: String) : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer(token, accountId)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

/** A head with a primary and a backup account, in front of [upstream]. */
private class PoolRig(val upstream: RejectingUpstream) {
    private val tmp = Files.createTempDirectory("v4242-foreign-key")
    private val primaryQuota = QuotaTracker(tmp.resolve("primary-quota.json"))
    private val backupQuota = QuotaTracker(tmp.resolve("backup-quota.json"))
    private val primaryAuth = SignInToken(PRIMARY_TOKEN, "primary-id")
    private val pool = AccountPool(
        listOf(
            account("primary", primary = true, primaryAuth, primaryQuota),
            account("backup", primary = false, SignInToken(BACKUP_TOKEN, "backup-id"), backupQuota),
        ),
        WallClock(AtomicLong(System.currentTimeMillis())::get),
    )
    private val head = HeadServer(
        provider = provider(),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000L, maxRetries = 2),
            quota = quotaFor(primaryQuota, pool, mapOf("primary" to primaryQuota, "backup" to backupQuota)),
        ),
    )
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }

    init {
        primaryQuota.record(quota(10.0))
        backupQuota.record(quota(37.0))
        runBlocking { head.start() }
        awaitListening(head.port)
    }

    fun turn(): String = runBlocking {
        client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "session-v4242")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
    }

    fun close() {
        runBlocking { head.stop() }
        client.close()
        upstream.stop()
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
        val reset = System.currentTimeMillis() / MS_PER_S + HOUR_S
        return QuotaSnapshot(
            fiveHour = QuotaWindow(used, reset, FIVE_HOURS_S),
            sevenDay = QuotaWindow(20.0, reset + WEEK_RESET_AFTER_S, WEEK_S),
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
            baseUrl = upstream.baseUrl,
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )
}

class ForeignKeyRejectionTest {
    @Test
    fun `a 401 naming a key the account never sent is reported as the upstream's failure`() {
        val rig = PoolRig(RejectingUpstream(rejection(FOREIGN_MASKED_KEY)))
        try {
            val first = rig.turn()
            assertTrue("Incorrect API key provided" in first, "the upstream's own words reach the client: $first")
            assertFalse("authentication_error" in first, "it is not the user's sign-in failing: $first")
            assertFalse("claudex login" in first, "so the client is not sent to sign in again: $first")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a 401 naming a key the account never sent leaves the account's credential usable`() {
        val rig = PoolRig(RejectingUpstream(rejection(FOREIGN_MASKED_KEY)))
        try {
            rig.turn()
            rig.turn()
            assertEquals(
                "Bearer $PRIMARY_TOKEN",
                rig.upstream.authorizations.last(),
                "the account's credential was not marked unavailable, so the next turn keeps it",
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a 401 naming the account's own key is still its sign-in failing`() {
        val rig = PoolRig(RejectingUpstream(rejection(OWN_MASKED_KEY)))
        try {
            val first = rig.turn()
            assertTrue("authentication_error" in first, "the credential this account sent was refused: $first")
            assertTrue("claudex login" in first, "and the remedy is to sign in again: $first")

            rig.turn()
            assertEquals(
                "Bearer $BACKUP_TOKEN",
                rig.upstream.authorizations.last(),
                "the refused credential is marked unavailable, so the next turn moves to the backup",
            )
        } finally {
            rig.close()
        }
    }

    private fun rejection(maskedKey: String): String =
        """{"error":{"message":"Incorrect API key provided: $maskedKey. You can find your API key at """ +
            """https://platform.openai.com/account/api-keys.","type":"invalid_request_error","param":null,""" +
            """"code":"invalid_api_key"}}"""
}

private const val UNAUTHORIZED = 401
private const val PRIMARY_TOKEN = "primary-token-v4242"
private const val BACKUP_TOKEN = "backup-token-v4242"

// Fakes in the shape the Codex backend masks a key: a visible head, a run of stars, a visible tail.
// The first is no key this test holds; the second masks the primary account's own token.
private val FOREIGN_MASKED_KEY = "sk-test0" + "*".repeat(40) + "Zq9x"
private val OWN_MASKED_KEY = "prima" + "*".repeat(12) + "4242"

private const val MS_PER_S = 1_000L
private const val HOUR_S = 3_600L
private const val WEEK_RESET_AFTER_S = 82_800L
private const val FIVE_HOURS_S = 18_000L
private const val WEEK_S = 604_800L
