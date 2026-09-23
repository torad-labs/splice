// NEW: 2026-09-22 — the daemon-start half of model discovery: every head asked at once, and no answer
// ever costs a head its start. The endpoint is a fake HeadModelsSource; the cache is real, under a
// temporary state directory, because surviving a restart is the cache's whole job.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.LogSink
import splice.models.discovery.Discovery
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds

private const val LIST_URL = "https://api.example.test/v1/models"

class ModelRostersTest {

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://api.example.test/v1",
        auth = AuthConfig("api-key", env = "TEST_API_KEY"),
    )
    private val lines = mutableListOf<String>()
    private val log = LogSink { synchronized(lines) { lines += it } }
    private val chat = listOf(DiscoveredModel("m-chat", contextWindow = 256_000), DiscoveredModel("m-2"))

    private fun rosters(tmp: Path, source: HeadModelsSource) =
        ModelRosters(StatePaths(baseOverride = tmp), log, source, deadline = 200.milliseconds)

    @Test
    fun `a head's answer is its roster, and is kept for the next start`(@TempDir tmp: Path) = runBlocking {
        val first = rosters(tmp, HeadModelsSource { _, _ -> Discovery.Found(LIST_URL, chat) })
        first.resolve(mapOf("xai" to provider))
        assertEquals(chat, first.forHead("xai"))
        assertEquals(emptyList<DiscoveredModel>(), first.forHead("unasked"))

        // The vendor is down at the next start: the picker keeps what it served last, so a session on
        // a discovered model survives the restart.
        val next = rosters(tmp, HeadModelsSource { _, _ -> Discovery.Unavailable(LIST_URL, "HTTP 503") })
        next.resolve(mapOf("xai" to provider))
        assertEquals(chat, next.forHead("xai"))
        assertTrue(lines.any { "HTTP 503" in it && "published last" in it }, "$lines")
    }

    @Test
    fun `no answer and nothing kept is the declared rows, said once`(@TempDir tmp: Path) = runBlocking {
        val rosters = rosters(tmp, HeadModelsSource { _, _ -> Discovery.Unavailable(LIST_URL, "HTTP 401") })
        rosters.resolve(mapOf("xai" to provider))
        assertEquals(emptyList<DiscoveredModel>(), rosters.forHead("xai"))
        assertEquals(1, lines.count { it.startsWith("[xai] models:") && "splice.toml's rows" in it }, "$lines")
    }

    @Test
    fun `an endpoint that never answers costs one deadline, and the other heads are unaffected`(@TempDir tmp: Path) =
        runBlocking {
            val never = CountDownLatch(1)
            val rosters = rosters(
                tmp,
                HeadModelsSource { key, _ ->
                    if (key == "stuck") never.await()
                    Discovery.Found(LIST_URL, chat)
                },
            )
            rosters.resolve(mapOf("stuck" to provider, "xai" to provider))
            assertEquals(emptyList<DiscoveredModel>(), rosters.forHead("stuck"))
            assertEquals(chat, rosters.forHead("xai"))
            assertTrue(lines.any { it.startsWith("[stuck] models: no answer within") }, "$lines")
        }

    @Test
    fun `a source that throws is that head's no-answer, never a failed start`(@TempDir tmp: Path) = runBlocking {
        val rosters = rosters(tmp, HeadModelsSource { _, _ -> error("credential file unreadable") })
        rosters.resolve(mapOf("xai" to provider))
        assertEquals(emptyList<DiscoveredModel>(), rosters.forHead("xai"))
        assertTrue(lines.any { it.startsWith("[xai] models: could not ask") }, "$lines")
    }
}
