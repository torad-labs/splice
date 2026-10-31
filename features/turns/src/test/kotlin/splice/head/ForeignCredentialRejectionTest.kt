// NEW: a 401 that names a key the account never sent is the upstream's failure.
//
// During the Codex outage of 2026-09-25 the HTTP side answered 401 invalid_api_key naming a masked
// OpenAI service key (`sk-svcac…fvMA`), while the account had sent its own sign-in token. The head
// read every 401 as the user's credential failing: the client was told authentication_error with
// "run: claudex login", and the account was marked unavailable, so the next turn left it. These pin
// both halves against a head with a real two-account pool, and pin the ordinary 401 beside them.
// Since failure-handling slice 2 the client reads the head's line for that fault, never the provider's body.
package splice.head

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
import org.junit.jupiter.api.io.TempDir
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
import splice.dialect.responses.ReasoningSettings
import splice.head.usage.QuotaTracker
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
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
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** An upstream that answers every request 401 with the [body] for the credential it carried, and records which
 *  credential each carried. */
private class RejectingUpstream(private val body: (String?) -> String) {
    val authorizations = CopyOnWriteArrayList<String?>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { ex ->
            ex.requestBody.use { it.transferTo(OutputStream.nullOutputStream()) }
            val authorization = ex.requestHeaders.getFirst("Authorization")
            authorizations += authorization
            val bytes = body(authorization).toByteArray()
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
private class PoolRig(root: Path, val upstream: RejectingUpstream) {
    private val tmp = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
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
            upstream = UpstreamClient(totalTimeoutMs = 30_000L),
        ).copy(
            quotaBundle = quotaFor(primaryQuota, pool, mapOf("primary" to primaryQuota, "backup" to backupQuota)),
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
            header("x-claude-code-session-id", "session")
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
            name = ProviderName(key = "codex", label = "claudex"),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = primaryAuth,
            locations = ProviderLocations(baseUrl = upstream.baseUrl),
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )
}

class ForeignCredentialRejectionTest(@param:TempDir private val root: Path) {
    @Test
    fun `a 401 naming a key the account never sent is reported as the upstream's failure`() {
        val rig = PoolRig(root, RejectingUpstream { rejection(FOREIGN_MASKED_KEY) })
        try {
            val first = rig.turn()
            // Failure-handling slice 2: the person reads the head's line for the provider's fault, and the
            // provider's own words (with the key it named) stay in the log (final-design.md section 10).
            assertTrue(FOREIGN_KEY_LINE in first, "the client reads that the provider failed its own check: $first")
            assertFalse(FOREIGN_MASKED_KEY in first, "a key never reaches a line: $first")
            assertFalse("authentication_error" in first, "it is not the user's sign-in failing: $first")
            assertFalse("claudex login" in first, "so the client is not sent to sign in again: $first")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a 401 naming a key the account never sent leaves the account's credential usable`() {
        val rig = PoolRig(root, RejectingUpstream { rejection(FOREIGN_MASKED_KEY) })
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
        // Each login's own key is named in its refusal, so neither is the provider's fault.
        val own = RejectingUpstream { sent ->
            rejection(if (sent == "Bearer $BACKUP_TOKEN") BACKUP_MASKED_KEY else OWN_MASKED_KEY)
        }
        val rig = PoolRig(root, own)
        try {
            val first = rig.turn()
            // Failure-handling slice 4: a login splice holds takes the held-login status, 403 permission_error,
            // which 2.1.310 does not send again; 401 authentication_error is kept for the login the client itself
            // forwarded, the one its own sign-in can renew (final-design.md section 11, record http-401/http-403).
            assertTrue("permission_error" in first, "the credential this account sent was refused: $first")
            assertTrue("claudex login" in first, "and the remedy is to sign in again: $first")

            assertEquals(
                listOf(PRIMARY_TOKEN, PRIMARY_TOKEN, BACKUP_TOKEN, BACKUP_TOKEN).map { "Bearer $it" },
                rig.upstream.authorizations,
                "each login is renewed once, then the turn moves to the next one before any byte (section 8)",
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
private const val FOREIGN_KEY_LINE =
    "[splice] claudex's provider rejected a key this login never sent, a fault on its side."
private const val PRIMARY_TOKEN = "primary-token"
private const val BACKUP_TOKEN = "backup-token"

// Fakes in the shape the Codex backend masks a key: a visible head, a run of stars, a visible tail.
// The first is no key this test holds; the second masks the primary account's own token.
private val FOREIGN_MASKED_KEY = "sk-test0" + "*".repeat(40) + "Zq9x"
private val OWN_MASKED_KEY = "prima" + "*".repeat(12) + "oken"
private val BACKUP_MASKED_KEY = "backu" + "*".repeat(12) + "oken"

private const val MS_PER_S = 1_000L
private const val HOUR_S = 3_600L
private const val WEEK_RESET_AFTER_S = 82_800L
private const val FIVE_HOURS_S = 18_000L
private const val WEEK_S = 604_800L
