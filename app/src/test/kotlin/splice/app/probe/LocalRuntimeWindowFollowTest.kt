package splice.app.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.auth.SignInPlanner
import splice.app.cli.status.LocalRuntimeReach
import splice.app.provider.HeadBuildInputs
import splice.app.provider.LiveRosterCatalog
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.LiveWindows
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.upstream.transport.LocalHttp
import splice.upstream.transport.LocalHttpReply
import java.nio.file.Path

/** #399: the window a local head advertises follows what its runtime serves now, not what it served at boot. */
class LocalRuntimeWindowFollowTest {
    private val provider = ProviderConfig(
        Dialect.OPENAI_CHAT,
        "http://localhost:1/v1",
        AuthConfig("api-key", env = "TEST_LOCAL_WINDOW_FOLLOW_KEY"),
    )
    private val head = HeadConfig("local", 0, "claude-local--", "synthetic")
    private val topology = Topology(providers = mapOf("local" to provider), heads = linkedMapOf("local" to head))

    /** The Ollama routes of one runtime that serves [window] tokens, or nothing at all while it is [down]. */
    private class Runtime {
        @Volatile var window: Long? = null

        val http = LocalHttp { method, url, _ ->
            val served = window ?: return@LocalHttp null
            when ("$method ${url.substringAfter("http://localhost:1")}") {
                "GET /api/version" -> LocalHttpReply(200, """{"version":"synthetic"}""")
                "GET /v1/models" -> LocalHttpReply(200, """{"data":[{"id":"synthetic"}]}""")
                "GET /api/ps" ->
                    LocalHttpReply(200, """{"models":[{"name":"synthetic","context_length":$served}]}""")
                "POST /api/show" -> LocalHttpReply(200, """{"model_info":{"synthetic.context_length":131072}}""")
                else -> LocalHttpReply(404, "{}")
            }
        }
    }

    @Test
    fun `a runtime that starts after the daemon, then restarts with another window, moves the head's window`(
        @TempDir tempDir: Path,
    ) {
        val runtime = Runtime()
        val inputs = HeadBuildInputs(
            ConfigService(StatePaths(baseOverride = tempDir), envReader = { null }),
            SignInPlanner(),
            localProbe = splice.app.provider.LocalProbeInputs(runtime.http),
        )
        val ctx = inputs.providerContext("local", head, provider, legacyKnobsGovern = false)
        val advertised = { LiveRosterCatalog(ctx, LiveWindows { null }).current().clientLaunchWindow }
        val watch = LocalRuntimeWatch(
            topology,
            LogSink {},
            LocalWindowRefresh(inputs::refreshLocalModels),
            LocalRuntimeReach(runtime.http, waitMs = 300L),
        )
        val atBoot = advertised()

        runtime.window = 8_192L
        watch.tick()
        val afterStart = advertised()
        runtime.window = 32_768L
        watch.tick()

        assertNotEquals(8_192L, atBoot, "the runtime was down at boot, so the head held a fallback")
        assertEquals(8_192L, afterStart)
        assertEquals(32_768L, advertised())
    }
}
