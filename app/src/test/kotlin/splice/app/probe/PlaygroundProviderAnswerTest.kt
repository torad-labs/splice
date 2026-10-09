// Real Playground replies reach the same retained provider facts the Models card reads.
package splice.app.probe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.pool.AccountsRoute
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.control.AccountHeadAdapter
import splice.app.head.HeadServerFactory
import splice.app.head.HeadServing
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.QuotaPollSeams
import splice.app.head.StartQuotaPoller
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.Wired
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.core.util.SecureFile
import splice.diagnostics.playground.PlaygroundResult
import splice.head.usage.ProviderReplyObserver
import splice.heads.HeadStatus
import splice.oauth.OAuthAccountFiles
import splice.upstream.Provider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlaygroundProviderAnswerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a Playground acceptance is retained on its assembled head`() = runTest {
        val fixture = fixture(backgroundScope)
        HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }).use { client ->
            val result = UpstreamPlaygroundProbe(fixture.second, client)
                .run(playgroundHead("claudex"), "synthetic prompt", null)
            assertTrue(result is PlaygroundResult)
            assertNotNull(fixture.first.head.providerAnswer(), "a real provider reply is not readiness unknown")
            val answer = requireNotNull(fixture.first.head.providerAnswer())
            assertEquals(200, answer.status)
            assertTrue(answer.accepted)
            val wire = HeadStatus.json(fixture.first.head, "chatgpt-oauth")["last_provider_answer"]!!.jsonObject
            assertEquals("200", wire["status"]!!.jsonPrimitive.content)
        }
        fixture.first.head.stop()
    }

    @Test
    fun `a Playground permission refusal is retained rather than reported as no observation`() = runTest {
        val fixture = fixture(backgroundScope)
        HttpClient(
            MockEngine {
                respond("""{"error":{"message":"does not have access"}}""", HttpStatusCode.Forbidden)
            },
        ).use { client ->
            UpstreamPlaygroundProbe(fixture.second, client).run(playgroundHead("claudex"), "synthetic prompt", null)
            val answer = fixture.first.head.providerAnswer()
            assertNotNull(answer)
            assertEquals(403, answer!!.status)
            assertEquals(false, answer.accepted)
        }
        fixture.first.head.stop()
    }

    @Test
    fun `a single-login roster reports its real 403 and clears it after a newer acceptance`() = runTest {
        val fixture = fixture(backgroundScope)
        val route = AccountsRoute(AccountHeadAdapter.adapt(mapOf("claudex" to fixture.first)))
        var refused = true
        HttpClient(
            MockEngine { respond("{}", if (refused) HttpStatusCode.Forbidden else HttpStatusCode.OK) },
        ).use { client ->
            val probe = UpstreamPlaygroundProbe(fixture.second, client)
            probe.run(playgroundHead("claudex"), "synthetic prompt", null)
            val row = Json.parseToJsonElement(route.accountsJson()).jsonObject.getValue("accounts").jsonArray.single()
            val refusal = row.jsonObject["last_refusal"]
            assertNotNull(refusal, "Accounts and Models must read the actual credential's same answer")
            assertEquals("403", requireNotNull(refusal).jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(
                fixture.first.head.providerAnswer()?.observedAtEpochMs.toString(),
                refusal.jsonObject.getValue("at_ms").jsonPrimitive.content,
            )
            refused = false
            probe.run(playgroundHead("claudex"), "synthetic prompt", null)
            val accounts = Json.parseToJsonElement(route.accountsJson()).jsonObject.getValue("accounts").jsonArray
            val cleared = accounts.single()
            assertEquals(JsonNull, cleared.jsonObject.getValue("last_refusal"))
        }
        fixture.first.head.stop()
    }

    @Test
    fun `a single-login Kimi head retains a legacy refusal on Models and Accounts after restart`() = runTest {
        SecureFile.writeAtomic0600(
            root.resolve("absent-auth.json"),
            """{"access_token":"synthetic-kimi","refresh_token":"synthetic-refresh","expires_at":4102444800}""",
        )
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val first = fixture(backgroundScope, "kimi", "kimi-oauth")
        first.first.head.stop()
        val answers = paths.ratelimitFile("kimi").resolveSibling(
            "${paths.ratelimitFile("kimi").fileName}.provider-answer",
        )
        SecureFile.writeAtomic0600(
            answers,
            """{"status":403,"observed_at_epoch_ms":1000,"accepted":false,"credentials":{}}""",
        )
        val restarted = fixture(backgroundScope, "kimi", "kimi-oauth")
        try {
            assertEquals("kimi-oauth", restarted.first.auth.describe().kind)
            assertEquals(403, restarted.first.head.providerAnswer()?.status)
            val route = AccountsRoute(AccountHeadAdapter.adapt(mapOf("kimi" to restarted.first)))
            val row = Json.parseToJsonElement(route.accountsJson()).jsonObject.getValue("accounts").jsonArray.single()
            val refusal = row.jsonObject.getValue("last_refusal")
            assertTrue(refusal is kotlinx.serialization.json.JsonObject, "the last head refusal is not Signed in")
            assertEquals("403", refusal.jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(
                "1000",
                refusal.jsonObject.getValue("at_ms").jsonPrimitive.content,
            )
            assertTrue(
                Json.parseToJsonElement(Files.readString(answers)).jsonObject
                    .getValue("credentials").jsonObject.isEmpty(),
                "a read must not stamp an unanswered credential onto a legacy observation",
            )
        } finally {
            restarted.first.head.stop()
        }
    }

    @Test
    fun `a keyed refusal cannot become a legacy fallback for an unanswered replacement`() = runTest {
        val first = fixture(backgroundScope)
        HttpClient(MockEngine { respond("{}", HttpStatusCode.Forbidden) }).use { client ->
            UpstreamPlaygroundProbe(first.second, client).run(playgroundHead("claudex"), "synthetic prompt", null)
        }
        first.first.head.stop()
        val replacement = login("replacement").toMutableMap()
        val tokens = replacement.getValue("tokens").jsonObject.toMutableMap()
        tokens["access_token"] = kotlinx.serialization.json.JsonPrimitive(
            tokens.getValue("access_token").jsonPrimitive.content + "-replacement",
        )
        replacement["tokens"] = kotlinx.serialization.json.JsonObject(tokens)
        SecureFile.writeAtomic0600(
            root.resolve("absent-auth.json"),
            kotlinx.serialization.json.JsonObject(replacement).toString(),
        )
        val restarted = fixture(backgroundScope)
        try {
            assertEquals(403, restarted.first.head.providerAnswer()?.status, "the head still retains its history")
            val route = AccountsRoute(AccountHeadAdapter.adapt(mapOf("claudex" to restarted.first)))
            val row = Json.parseToJsonElement(route.accountsJson()).jsonObject.getValue("accounts").jsonArray.single()
            assertEquals(JsonNull, row.jsonObject.getValue("last_refusal"), "the new credential has never answered")
        } finally {
            restarted.first.head.stop()
        }
    }

    @Test
    fun `a Playground quota reset reaches the head and a later real acceptance clears it`() = runTest {
        val fixture = fixture(backgroundScope)
        var refused = true
        HttpClient(
            MockEngine {
                if (refused) {
                    respond(
                        """{"error":{"message":"Subscription quota exhausted","resets_in_seconds":3600}}""",
                        HttpStatusCode.TooManyRequests,
                    )
                } else {
                    respond("{}", HttpStatusCode.OK)
                }
            },
        ).use { client ->
            val probe = UpstreamPlaygroundProbe(fixture.second, client)
            probe.run(playgroundHead("claudex"), "synthetic prompt", null)
            assertEquals(429, fixture.first.head.providerAnswer()?.status)
            assertTrue(fixture.first.head.providerResetForMs() in 1L..3_600_000L, "the named reset is retained")
            refused = false
            probe.run(playgroundHead("claudex"), "synthetic prompt", null)
            assertEquals(200, fixture.first.head.providerAnswer()?.status)
            assertEquals(
                0L,
                fixture.first.head.providerResetForMs(),
                "a real acceptance ends the same credential's hold",
            )
        }
        fixture.first.head.stop()
    }

    @Test
    fun `a Playground acceptance posted before a newer refusal cannot clear its hold`() = runTest {
        val fixture = fixture(backgroundScope)
        val posted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        HttpClient(
            MockEngine {
                if (requests++ == 0) {
                    posted.complete(Unit)
                    release.await()
                    respond("{}", HttpStatusCode.OK)
                } else {
                    respond(
                        """{"error":{"message":"Subscription quota exhausted","resets_in_seconds":3600}}""",
                        HttpStatusCode.TooManyRequests,
                    )
                }
            },
        ).use { client ->
            val probe = UpstreamPlaygroundProbe(fixture.second, client)
            val older = async { probe.run(playgroundHead("claudex"), "synthetic prompt", null) }
            try {
                posted.await()
                probe.run(playgroundHead("claudex"), "synthetic prompt", null)
                assertTrue(fixture.first.head.providerResetForMs() > 0L)
                release.complete(Unit)
                assertTrue(older.await() is PlaygroundResult)
                assertTrue(
                    fixture.first.head.providerResetForMs() > 0L,
                    "late accepted headers cannot erase a refusal learned after this send",
                )
            } finally {
                release.complete(Unit)
                older.await()
            }
        }
        fixture.first.head.stop()
    }

    @Test
    fun `headers are observed while the response body is still held open`() = runTest {
        val fixture = fixture(backgroundScope)
        val body = ByteChannel(autoFlush = true)
        val headers = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) }) {
            install(
                createClientPlugin("SyntheticHeaders") {
                    onResponse { headers.complete(Unit) }
                },
            )
        }
        client.use {
            val send = async {
                UpstreamPlaygroundProbe(fixture.second, client)
                    .run(playgroundHead("claudex"), "synthetic prompt", null)
            }
            try {
                headers.await()
                runCurrent()
                assertNotNull(
                    fixture.first.head.providerAnswer(),
                    "headers, not full body completion, prove acceptance",
                )
            } finally {
                body.close()
                send.await()
            }
        }
        fixture.first.head.stop()
    }

    @Test
    fun `a local send failure cannot masquerade as a provider refusal`() = runTest {
        val fixture = fixture(backgroundScope)
        HttpClient(MockEngine { throw java.io.IOException("synthetic connection refused") }).use { client ->
            UpstreamPlaygroundProbe(fixture.second, client).run(playgroundHead("claudex"), "synthetic prompt", null)
            assertNull(fixture.first.head.providerAnswer())
            assertEquals(0L, fixture.first.head.providerResetForMs())
        }
        fixture.first.head.stop()
    }

    @Test
    fun `a pooled send holds only its captured login and the head is limited only when both are held`() = runTest {
        val primary = root.resolve("absent-auth.json")
        SecureFile.writeAtomic0600(primary, login("primary").toString())
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, primary, "backup", login("backup"))
        val fixture = fixture(backgroundScope)
        val engine = MockEngine {
            respond(
                """{"error":{"message":"Subscription quota exhausted","resets_in_seconds":3600}}""",
                HttpStatusCode.TooManyRequests,
            )
        }
        HttpClient(engine).use { client ->
            val probe = UpstreamPlaygroundProbe(fixture.second, client)
            val first = probe.run(playgroundHead("claudex"), "synthetic prompt", null) as PlaygroundResult
            assertEquals("primary", first.request.jsonObject["account"]!!.jsonPrimitive.content)
            assertEquals(0L, fixture.first.head.providerResetForMs(), "the backup is not the refused credential")
            val second = probe.run(playgroundHead("claudex"), "synthetic prompt", null) as PlaygroundResult
            assertEquals("backup", second.request.jsonObject["account"]!!.jsonPrimitive.content)
            assertTrue(
                fixture.first.head.providerResetForMs() in 1L..3_600_000L,
                "both actual logins reported their reset",
            )
        }
        fixture.first.head.stop()
    }

    @Test
    fun `a replacement during credential I O cannot receive the older head reply`() = runTest {
        val fixture = fixture(backgroundScope)
        val registry = fixture.second
        val provider = requireNotNull(registry["claudex"])
        val observer = requireNotNull(registry.target("claudex")?.observer)
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials {
                reading.complete(Unit)
                release.await()
                return Credentials.Bearer("synthetic-old")
            }
            override suspend fun refresh(): Credentials = credentials()
            override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
        }
        registry.bind("claudex", Wired(provider, auth), null, observer)
        var replacementReplies = 0
        var request: HttpRequestData? = null
        HttpClient(
            MockEngine {
                request = it
                respond("{}", HttpStatusCode.OK)
            },
        ).use { client ->
            val send = async {
                UpstreamPlaygroundProbe(registry, client)
                    .run(splice.diagnostics.playground.PlaygroundHead("claudex", auth), "synthetic prompt", null)
            }
            reading.await()
            val replacement = object : Provider by provider {
                override val upstreamUrl: String = "https://replacement.synthetic.example/responses"
            }
            val nextAuth = object : RefreshableAuthProvider by auth {
                override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-new")
            }
            registry.bind(
                "claudex",
                Wired(replacement, nextAuth),
                null,
                ProviderReplyObserver { _, _ -> replacementReplies++ },
            )
            release.complete(Unit)
            send.await()
            assertEquals(provider.upstreamUrl, requireNotNull(request).url.toString())
            assertEquals("Bearer synthetic-old", requireNotNull(request).headers["Authorization"])
            assertEquals(0, replacementReplies, "the replacement cannot own a reply it did not send")
            assertEquals(200, fixture.first.head.providerAnswer()?.status)
        }
        fixture.first.head.stop()
    }

    private fun login(account: String): kotlinx.serialization.json.JsonObject {
        val encoded = Base64.getUrlEncoder().withoutPadding()
        val header = encoded.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = encoded.encodeToString("""{"exp":4102444800}""".toByteArray())
        return buildJsonObject {
            putJsonObject("tokens") {
                put("access_token", "$header.$payload.synthetic")
                put("refresh_token", "synthetic")
                put("account_id", account)
            }
        }
    }

    private fun fixture(
        scope: CoroutineScope,
        keyName: String = "claudex",
        authKind: String = "chatgpt-oauth",
    ): Pair<splice.app.control.ManagedHead, PlaygroundProviders> {
        val primary = root.resolve("absent-auth.json")
        if (!java.nio.file.Files.exists(primary)) SecureFile.writeAtomic0600(primary, login("primary").toString())
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val config = ConfigService(paths)
        val key = MgmtKey(paths)
        val planner = SignInPlanner()
        val registry = PlaygroundProviders()
        val factory = ManagedHeadFactory(
            statePaths = paths,
            providerAssembly = ProviderAssembly(
                paths,
                scope,
                {},
                TokenUrlRefreshCall { _, _ -> error("synthetic assembly must not refresh") },
            ),
            serving = HeadServing(HeadServerFactory(config, key, {}), registry),
            launchSpecFactory = LaunchSpecFactory(
                Topology(),
                planner,
                key,
                HeadBuildInputs(config, planner),
            ),
            log = {},
            quotaSeams = QuotaPollSeams(
                scope,
                {},
                startQuotaPoller = StartQuotaPoller { _, _, _, _ -> null },
            ),
        )
        val model = ModelEntry("synthetic-model", contextWindow = 4_000)
        val kimi = authKind == "kimi-oauth"
        val head = HeadConfig(if (kimi) "kimi" else "synthetic", 3099, "synthetic--", model.id)
        val provider = ProviderConfig(
            dialect = if (kimi) Dialect.ANTHROPIC_PASSTHROUGH else Dialect.OPENAI_RESPONSES,
            baseUrl = "https://synthetic.example/responses",
            auth = AuthConfig(authKind, file = root.resolve("absent-auth.json").toString()),
        )
        val build = ProviderBuild(
            key = keyName,
            head = head,
            providerCfg = provider,
            catalog = ModelCatalog("synthetic--", listOf(model), defaultContextWindow = model.contextWindow),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = config.getConfig(keyName),
            loginCommand = "synthetic login",
        )
        return factory.assembleHead(build, 3098) to registry
    }
}
