// NEW: 2026-09-22 — the daemon's half of model discovery: what a head's endpoint answer becomes, and
// the list kept across starts for when the endpoint does not answer. No socket: the HTTP seam is a
// lambda, and the cache lives under a temporary state directory.
package splice.models.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.models.list.ModelCredentialSource
import splice.models.list.ModelsAnswer
import splice.models.list.ModelsProbe
import java.nio.file.Files
import java.nio.file.Path

class ModelDiscoveryTest {

    private val env = EnvReader { null }
    private val credentials = ModelCredentialSource { _, _, _ -> "key-abc" }
    private val asked = mutableListOf<String>()

    private val remote = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://api.example.test/v1",
        auth = AuthConfig("api-key", env = "TEST_API_KEY"),
    )

    private fun discovery(answer: ModelsAnswer) =
        ModelDiscovery(ModelsProbe(http = { url, _ -> answer.also { asked += url } }, credentials = credentials))

    @Test
    fun `a published list becomes the models that can run a turn`() {
        val body = """
            {"data":[
              {"id":"m-chat","display_name":"Chat","context_length":256000,"aliases":["m-chat-latest"]},
              {"id":"m-image","output_modalities":["image"]},
              {"slug":"m-hidden","visibility":"hide"}
            ]}
        """.trimIndent()
        val found = discovery(ModelsAnswer.Answered(200, body)).discover("test", remote, env)
        assertTrue(found is Discovery.Found, "got $found")
        found as Discovery.Found
        assertEquals("https://api.example.test/v1/models", found.url)
        assertEquals(listOf(DiscoveredModel("m-chat", "Chat", 256_000, listOf("m-chat-latest"))), found.models)
    }

    @Test
    fun `a refusal or an unreadable body is unavailable, and names where it asked`() {
        val refused = discovery(ModelsAnswer.Answered(401, "")).discover("test", remote, env)
        assertTrue(refused is Discovery.Unavailable)
        assertEquals("https://api.example.test/v1/models", (refused as Discovery.Unavailable).url)
        val garbled = discovery(ModelsAnswer.Answered(200, "<html>")).discover("test", remote, env)
        assertTrue(garbled is Discovery.Unavailable)
    }

    @Test
    fun `a local runtime is not asked`() {
        val local = remote.copy(baseUrl = "http://127.0.0.1:8099/v1")
        val answer = discovery(ModelsAnswer.Answered(200, """{"models":[{"id":"/packs/a.gguf"}]}""")).discover("test", local, env)
        assertTrue(answer is Discovery.Unavailable)
        assertNull((answer as Discovery.Unavailable).url)
        assertTrue(asked.isEmpty(), "a local runtime's list names a file, so it is never asked")
    }

    @Test
    fun `the kept list round-trips, and only for the url that produced it`(@TempDir tmp: Path) {
        val cache = RosterCache(StatePaths(baseOverride = tmp))
        val models = listOf(DiscoveredModel("m-chat", "Chat", 256_000, listOf("m-chat-latest")), DiscoveredModel("m-2"))
        assertNull(cache.read("test", remote), "nothing kept yet")
        cache.write("test", Discovery.Found("https://api.example.test/v1/models", models))
        assertEquals(models, cache.read("test", remote))
        // A moved endpoint is a different endpoint: its old answer says nothing about the new one.
        assertNull(cache.read("test", remote.copy(modelsUrl = "https://elsewhere.test/models")))
        assertNull(cache.read("other-head", remote), "each head keeps its own list")
    }

    @Test
    fun `a kept list that cannot be read is no list`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp)
        Files.createDirectories(paths.modelRosterFile("test").parent)
        Files.writeString(paths.modelRosterFile("test"), "{not json")
        assertNull(RosterCache(paths).read("test", remote))
    }
}
