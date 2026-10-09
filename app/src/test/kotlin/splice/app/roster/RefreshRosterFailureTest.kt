// A failed roster refresh never discards the last live roster, even if its kept file is gone.
package splice.app.roster

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.HeadModelsSource
import splice.app.provider.ModelRosters
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.ModelRates
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.models.discovery.Discovery
import splice.models.discovery.KeptRoster
import splice.models.discovery.RosterCache
import java.nio.file.Files
import java.nio.file.Path

private const val URL = "https://synthetic.example.test/v1/models"

class RefreshRosterFailureTest {
    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://synthetic.example.test/v1",
        auth = AuthConfig("api-key", env = "SYNTHETIC_KEY"),
    )

    @Test
    fun `failed refresh retains live models when the kept file is unavailable and logs once`(@TempDir tmp: Path) =
        runBlocking {
            val paths = StatePaths(baseOverride = tmp)
            val models = listOf(DiscoveredModel("synthetic-original"))
            var answer: Discovery = Discovery.Found(URL, models)
            val logs = mutableListOf<String>()
            val rosters = ModelRosters(paths, { logs.add(it) }, HeadModelsSource { _, _ -> answer })
            rosters.resolve(mapOf("synthetic" to provider))
            Files.delete(paths.modelRosterFile("synthetic"))
            logs.clear()
            answer = Discovery.Unavailable(URL, "synthetic HTTP 503")

            rosters.resolve(mapOf("synthetic" to provider))

            assertEquals(models, rosters.forHead("synthetic"))
            assertEquals(1, logs.size, logs.toString())
            assertTrue("synthetic HTTP 503" in logs.single(), logs.toString())
        }

    @Test
    fun `a successful refresh rewrites the kept roster including newly listed rates`(@TempDir tmp: Path) = runBlocking {
        val paths = StatePaths(baseOverride = tmp)
        var models = listOf(DiscoveredModel("synthetic-original"))
        val rosters = ModelRosters(paths, {}, HeadModelsSource { _, _ -> Discovery.Found(URL, models) })
        rosters.resolve(mapOf("synthetic" to provider))
        val next = DiscoveredModel("synthetic-new", rates = ModelRates(input = 2.0, cacheRead = 0.5, output = 8.0))
        models = models + next

        rosters.resolve(mapOf("synthetic" to provider))

        assertEquals(models, rosters.forHead("synthetic"))
        assertEquals(models, (RosterCache(paths).read("synthetic", provider) as KeptRoster.Kept).models)
    }
}
