package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.HeadAssembly
import splice.app.auth.SignInPlanner
import splice.app.head.HeadBoot
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.oauth.grok.GrokRefresh
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.nio.file.Path

/** v0.4.0 §10: a local head is built from what its user-managed runtime serves, and a runtime that
 *  is up and contradicts the row refuses the head (reported DEGRADED) — with no request, at any
 *  step, leaving the runtime's own address for a hosted provider. */
class LocalHeadBootTest {
    private val scope = CoroutineScope(Job())
    private val logs = mutableListOf<String>()
    private val seen = mutableListOf<String>()

    @AfterEach
    fun stop() = scope.cancel()

    private val base = "http://localhost:1/v1"
    private val head = HeadConfig("local", 4601, "claude-local--", "synthetic")

    private fun provider(window: Long? = null) = ProviderConfig(
        Dialect.OPENAI_CHAT,
        base,
        AuthConfig("api-key", env = "TEST_LOCAL_HEAD_BOOT_API_KEY"),
        models = window?.let { listOf(ModelEntry("synthetic", contextWindow = it)) }.orEmpty(),
    )

    /** An Ollama-shaped runtime listing [listed]; every request is recorded and none may leave localhost:1. */
    private fun runtime(listed: String) = LocalHttp { method, url, _ ->
        seen += "$method $url"
        check(url.startsWith("http://localhost:1/")) { "request left the local runtime: $url" }
        check(!url.endsWith("/chat/completions")) { "boot must never generate a chat request: $url" }
        val path = url.removePrefix("http://localhost:1")
        when ("$method $path") {
            "GET /api/version" -> LocalHttpReply(200, """{"version":"synthetic"}""")
            "GET /v1/models" -> LocalHttpReply(200, """{"data":[{"id":"$listed"}]}""")
            "GET /api/ps" -> LocalHttpReply(200, """{"models":[{"name":"$listed","context_length":8192}]}""")
            "POST /api/show" -> LocalHttpReply(200, """{"model_info":{"synthetic.context_length":131072}}""")
            else -> null
        }
    }

    private fun inputs(tempDir: Path, listed: String) = HeadBuildInputs(
        ConfigService(StatePaths(baseOverride = tempDir), envReader = { null }),
        SignInPlanner(),
        localProbe = LocalProbeInputs(runtime(listed)),
    )

    private fun arm() = ChatArm(scope, { logs += it }, GrokRefresh(LogSink {}))

    @Test
    fun `a runtime that is up and lists the model builds the head`(@TempDir tempDir: Path) {
        val cfg = provider(window = 8192)
        val ctx = inputs(tempDir, "synthetic").providerContext("local", head, cfg, legacyKnobsGovern = false)

        val wired = arm().chatProvider(ctx, "local")

        assertEquals("http://localhost:1/v1/chat/completions", wired.provider.upstreamUrl)
        assertTrue(logs.any { it.contains("1 row(s) validated") }, "$logs")
        assertTrue(seen.isNotEmpty() && seen.all { it.contains("http://localhost:1/") }, "$seen")
    }

    @Test
    fun `a runtime that lacks the model refuses the head with its own words`(@TempDir tempDir: Path) {
        val ctx = inputs(tempDir, "other").providerContext("local", head, provider(), legacyKnobsGovern = false)

        val refusal = assertThrows(IllegalStateException::class.java) { arm().chatProvider(ctx, "local") }

        assertTrue(refusal.message!!.contains("refuses 'synthetic': not listed by the runtime (listed: other)"), "${refusal.message}")
        assertTrue(seen.all { it.startsWith("GET http://localhost:1/") || it.startsWith("POST http://localhost:1/") })
    }

    @Test
    fun `a row declaring more context than the runtime serves is refused and the head is degraded`(
        @TempDir tempDir: Path,
    ) {
        val cfg = provider(window = 16384)
        val build = inputs(tempDir, "synthetic")
        val topology = Topology(providers = mapOf("local" to cfg), heads = mapOf("local" to head))
        val arm = arm()

        val failed = HeadBoot().assembleDaemonHeads(
            topology,
            StatePaths(baseOverride = tempDir.resolve("state")),
            mutableMapOf(),
            { logs += it },
            HeadAssembly { key, h, c ->
                arm.chatProvider(build.providerContext(key, h, c, legacyKnobsGovern = false), key)
                error("the row was supposed to be refused before a head existed")
            },
        )

        assertEquals(setOf("local"), failed.keys, "the refused head is degraded")
        assertTrue(logs.any { it.contains("[local][boot] SKIPPED (build failed)") }, "$logs")
        assertTrue(
            logs.any { it.contains("refuses 'synthetic'") && it.contains("runtime serves 8192") },
            "the runtime's own words reach the log: $logs",
        )
        assertTrue(seen.isNotEmpty() && seen.all { it.contains("http://localhost:1/") }, "$seen")
    }
}
