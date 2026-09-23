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
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ModelDiscoveryConfig
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

    // The operator reads this line to learn what joined the picker. It once said "385 discovered" for
    // an OpenRouter head whose filter let 294 join (2026-09-23), so the filter's share is named on it.
    // A model a declared row covers is in the picker whatever the filter says, so it is not counted as
    // kept out; the rows the endpoint itself rules out are counted, or they vanish from the line.
    @Test
    fun `the line counts what the endpoint listed and what stays out of the picker`(@TempDir tmp: Path) =
        runBlocking {
            val rosters = rosters(
                tmp,
                HeadModelsSource { key, _ -> Discovery.Found(LIST_URL, chat, ruledOut = if (key == "cx") 2 else 0) },
            )
            val filtered = provider.copy(discovery = ModelDiscoveryConfig(exclude = listOf("m-2")))
            val declared = filtered.copy(models = listOf(ModelEntry("m-2", contextWindow = 128_000)))
            rosters.resolve(mapOf("xai" to provider, "or" to filtered, "dec" to declared, "cx" to provider))
            assertTrue("[xai] models: 2 listed at $LIST_URL\n" in lines, "$lines")
            assertTrue("[or] models: 2 listed at $LIST_URL, 1 kept out by its discovery filter\n" in lines, "$lines")
            assertTrue("[dec] models: 2 listed at $LIST_URL\n" in lines, "$lines")
            assertTrue("[cx] models: 4 listed at $LIST_URL, 2 it marks unusable for a turn\n" in lines, "$lines")
        }

    @Test
    fun `no answer and nothing kept is the declared rows, said once with where it asked`(@TempDir tmp: Path) =
        runBlocking {
            val rosters = rosters(tmp, HeadModelsSource { _, _ -> Discovery.Unavailable(LIST_URL, "HTTP 401") })
            rosters.resolve(mapOf("xai" to provider))
            assertEquals(emptyList<DiscoveredModel>(), rosters.forHead("xai"))
            val said = "[xai] models: HTTP 401 (asked at $LIST_URL); " +
                "no list was kept, so the picker is splice.toml's rows\n"
            assertEquals(listOf(said), lines.filter { it.startsWith("[xai] models:") })
        }

    // 2026-09-23 (review): a moved endpoint, a corrupt file and an absent one all read "the picker is
    // splice.toml's rows", which left the operator to guess which of three fixes applied.
    @Test
    fun `a kept list from another endpoint is named, not used`(@TempDir tmp: Path) = runBlocking {
        rosters(tmp, HeadModelsSource { _, _ -> Discovery.Found(LIST_URL, chat) }).resolve(mapOf("xai" to provider))
        val moved = provider.copy(modelsUrl = "https://moved.example.test/models")
        val next = rosters(tmp, HeadModelsSource { _, _ -> Discovery.Unavailable(null, "HTTP 503") })
        next.resolve(mapOf("xai" to moved))
        assertEquals(emptyList<DiscoveredModel>(), next.forHead("xai"))
        assertTrue(lines.any { "the list kept was published at $LIST_URL" in it }, "$lines")
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
            val stuck = lines.single { it.startsWith("[stuck] models:") }
            assertTrue(stuck.startsWith("[stuck] models: no answer within") && "(asked at $LIST_URL)" in stuck, stuck)
        }

    @Test
    fun `a source that throws is that head's no-answer, never a failed start`(@TempDir tmp: Path) = runBlocking {
        val rosters = rosters(tmp, HeadModelsSource { _, _ -> error("credential file unreadable") })
        rosters.resolve(mapOf("xai" to provider))
        assertEquals(emptyList<DiscoveredModel>(), rosters.forHead("xai"))
        assertTrue(lines.any { it.startsWith("[xai] models: could not ask") }, "$lines")
    }
}
