// NEW: synthetic native-login assembly shared by selector, quota and captured-owner controls.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import splice.accounts.claude.ClaudeAccountIdentity
import splice.app.ControlPlane
import splice.app.TokenUrlRefreshCall
import splice.app.control.ControlServer
import splice.app.control.DashboardPage
import splice.app.control.ManagedHead
import splice.app.control.TurnPathStalled
import splice.app.daemon.BootedTopology
import splice.app.head.HeadServerFactory
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.StartQuotaPoller
import splice.app.provider.ProviderBuild
import splice.core.auth.CredentialKey
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.head.usage.CredentialQuotaFiles
import splice.models.roster.DeclaredHead
import splice.models.roster.DeclaredHeads
import splice.topology.TopologyLoader
import splice.usage.quota.ClientUserAgent
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbes
import splice.usage.quota.QuotaSnapshotSink
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal class ClaudeNativePoolFixture(private val home: Path) {
    val paths = StatePaths(baseOverride = home.resolve(".splice/state"))

    fun seed(place: String, expiresAt: Long = NATIVE_ACCESS_EXPIRY, quota: QuotaSnapshot? = null) {
        val directory = home.resolve(if (place == "native") ".claude" else ".claude-splice")
        Files.createDirectories(directory)
        val token = "synthetic-$place"
        Files.writeString(
            directory.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"must-not-be-used","expiresAt":$expiresAt}}""",
        )
        Files.writeString(directory.resolve(".claude.json"), """{"oauthAccount":{"accountUuid":"copied-stale"}}""")
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key, ClaudeAccountIdentity("account-$place", null))
        CredentialQuotaFiles(paths.quotaFile(NATIVE_HEAD), {}).observed(
            key,
            quota ?: QuotaSnapshot(
                plan = "synthetic",
                fiveHour = QuotaWindow(10.0, if (place == "native") 4_102_000_000L else 4_102_100_000L, 18_000L),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun delayedClient(entered: CompletableDeferred<Unit>, released: CompletableDeferred<Unit>): HttpClient = HttpClient(
        MockEngine {
            if (it.headers["Authorization"] == "Bearer synthetic-native") {
                entered.complete(Unit)
                released.await()
                respond(
                    """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic old refusal"}}""",
                    HttpStatusCode.TooManyRequests,
                    headersOf(
                        "anthropic-ratelimit-unified-status" to listOf("rejected"),
                        "anthropic-ratelimit-unified-representative-claim" to listOf("seven_day"),
                        "anthropic-ratelimit-unified-reset" to listOf(
                            (System.currentTimeMillis() / 1_000L + 3_600L).toString(),
                        ),
                    ),
                )
            } else {
                assertEquals("Bearer synthetic-replacement", it.headers["Authorization"])
                respond("{}", HttpStatusCode.OK)
            }
        },
    )

    fun replaceNative(): Path {
        val file = home.resolve(".claude/.credentials.json")
        Files.writeString(
            file,
            """{"claudeAiOauth":{"accessToken":"synthetic-replacement","expiresAt":$NATIVE_ACCESS_EXPIRY}}""",
        )
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-replacement")))
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key, ClaudeAccountIdentity("replacement", null))
        return file
    }

    private fun topologyFile(upstreamUrl: String): Path {
        val file = home.resolve("splice.toml")
        Files.writeString(
            file,
            """
            [providers.native]
            dialect = "anthropic-passthrough"
            base_url = "$upstreamUrl"
            [providers.native.auth]
            kind = "client"
            [heads.claude-splice]
            provider = "native"
            port = 3100
            discovery_prefix = "synthetic--"
            [heads.claude-splice.claude]
            config_dir = "~/.claude-splice"
            """.trimIndent(),
        )
        return file
    }

    private fun configuration(probes: QuotaProbes?): ConfigService = ConfigService(
        paths,
        headOverrides = mapOf(
            "quotaPoll" to if (probes == null) "off" else "on",
            "upstreamRetries" to "0",
        ),
    )

    private fun plane(file: Path, config: ConfigService, key: MgmtKey): ControlPlane = ControlPlane(
        paths,
        config,
        key,
        DashboardPage { "<!doctype html>" },
        {},
        {},
        BootedTopology(
            path = file,
            declaredHeads = DeclaredHeads { mapOf(NATIVE_HEAD to DeclaredHead("native", null)) },
        ),
        TokenUrlRefreshCall { _, _ -> error("native credentials must never be refreshed") },
    )

    suspend fun rig(probes: QuotaProbes? = null, upstreamUrl: String = "https://synthetic.example"): Rig {
        val file = topologyFile(upstreamUrl)
        val topology = TopologyLoader.parse(Files.readString(file))
        val config = configuration(probes)
        val key = MgmtKey(paths)
        val plane = plane(file, config, key)
        val model = ModelEntry("synthetic-model", contextWindow = 4_000)
        val factory = ManagedHeadFactory(
            paths,
            plane.providerAssembly,
            HeadServerFactory(config, key, {}).also {
                it.sentCredentials = plane.sentCredentials
                it.credentialAccountNames = plane.credentialAccountNames
            },
            LaunchSpecFactory(topology, plane.signInPlanner, key, plane.buildInputs),
            plane.probeScope,
            {},
            startQuotaPoller = StartQuotaPoller { head, probe, tracker, interval ->
                check(probes != null) { "polling was not enabled in this fixture" }
                QuotaPoller(
                    plane.probeScope,
                    head,
                    probe,
                    QuotaSnapshotSink(tracker::record),
                    {},
                    intervalMs = interval,
                )
                    .also { it.start() }
            },
            clientUserAgent = ClientUserAgent { "synthetic-client" },
            playgroundProviders = plane.playgroundProviders,
        )
        probes?.let { factory.quotaProbes = it }
        val ctx = ProviderBuild(
            NATIVE_HEAD,
            topology.heads.getValue(NATIVE_HEAD).copy(port = 0),
            topology.providers.getValue("native"),
            ModelCatalog("synthetic--", listOf(model), defaultContextWindow = 4_000),
            WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            config.getConfig(NATIVE_HEAD),
            "synthetic login",
        )
        val head = factory.assembleHead(ctx, 0)
        head.head.start()
        val server = requireNotNull(
            plane.start(0, mapOf(NATIVE_HEAD to head), { 0 }, 1, TurnPathStalled { emptyList() }),
        )
        return Rig(plane, head, server, key)
    }

    fun nativeUpstream(
        sent: MutableList<String?>,
        bodies: MutableList<String>,
        refusedCredential: String? = "Bearer synthetic-native",
    ) =
        embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                post("/v1/messages") {
                    val credential = call.request.headers["Authorization"]
                    sent.add(credential)
                    bodies.add(call.receiveText())
                    if (credential == refusedCredential) {
                        call.response.header("anthropic-ratelimit-unified-status", "rejected")
                        call.response.header("anthropic-ratelimit-unified-representative-claim", "seven_day")
                        call.response.header(
                            "anthropic-ratelimit-unified-reset",
                            (System.currentTimeMillis() / 1_000L + 3_600L).toString(),
                        )
                        call.respondText(
                            """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic weekly limit"}}""",
                            ContentType.Application.Json,
                            HttpStatusCode.TooManyRequests,
                        )
                    } else {
                        call.respondText(
                            """
                            event: message_start
                            data: {"type":"message_start","message":{"id":"msg_synthetic","type":"message","role":"assistant","model":"synthetic-model","content":[],"stop_reason":null,"usage":{"input_tokens":1,"output_tokens":0}}}

                            event: content_block_start
                            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                            event: content_block_delta
                            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                            event: content_block_stop
                            data: {"type":"content_block_stop","index":0}

                            event: message_delta
                            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                            event: message_stop
                            data: {"type":"message_stop"}

                            """.trimIndent() + "\n\n",
                            ContentType.Text.EventStream,
                        )
                    }
                }
            }
        }

    class Rig(
        val plane: ControlPlane,
        val head: ManagedHead,
        val server: ControlServer,
        val key: MgmtKey,
    ) {
        suspend fun close() {
            server.stop()
            head.head.stop()
            plane.cancelProbes()
        }
    }
}
