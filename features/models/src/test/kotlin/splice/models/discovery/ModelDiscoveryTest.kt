// NEW: 2026-09-22 — the daemon's half of model discovery: what a head's endpoint answer becomes, and
// the list kept across starts for when the endpoint does not answer. No socket: the HTTP seam is a
// lambda, and the cache lives under a temporary state directory.
package splice.models.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import splice.models.list.ProbedProvider
import splice.models.list.UpstreamRoster
import splice.models.list.UpstreamRosterParser
import java.nio.file.Files
import java.nio.file.Path

private const val LIST_URL = "https://api.example.test/v1/models"

class ModelDiscoveryTest {

    private val env = EnvReader { null }
    private val credentialReads = mutableListOf<String>()
    private val credentials = ModelCredentialSource { _, key, _ -> "key-abc".also { credentialReads += key } }
    private val discovery = ModelDiscovery(credentials)

    private val remote = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://api.example.test/v1",
        auth = AuthConfig("api-key", env = "TEST_API_KEY"),
    )

    /** What the probe hands discovery for [body] — the real parser, no socket. */
    private fun answered(body: String): Discovery =
        discovery.answer(ProbedProvider("test", remote, LIST_URL, UpstreamRosterParser().parse(body, LIST_URL)))

    @Test
    fun `a published list becomes the models that can run a turn`() {
        val body = """
            {"data":[
              {"id":"m-chat","display_name":"Chat","context_length":256000,"aliases":["m-chat-latest"]},
              {"id":"m-image","output_modalities":["image"]},
              {"slug":"m-hidden","visibility":"hide"}
            ]}
        """.trimIndent()
        val found = answered(body)
        assertTrue(found is Discovery.Found, "got $found")
        found as Discovery.Found
        assertEquals(LIST_URL, found.url)
        assertEquals(listOf(DiscoveredModel("m-chat", "Chat", 256_000, listOf("m-chat-latest"))), found.models)
        assertEquals(2, found.ruledOut, "the image and hidden rows are counted, so daemon.log can say so")
    }

    @Test
    fun `an unreadable or unpublished list is unavailable, and names where it asked`() {
        val garbled = answered("<html>sign in</html>")
        assertTrue(garbled is Discovery.Unavailable, "got $garbled")
        assertEquals(LIST_URL, (garbled as Discovery.Unavailable).url)
        val unpublished = ProbedProvider("test", remote, LIST_URL, UpstreamRoster.Unpublished("no list"))
        assertEquals(Discovery.Unavailable(LIST_URL, "no list"), discovery.answer(unpublished))
    }

    @Test
    fun `a local runtime is not asked`() {
        val local = remote.copy(baseUrl = "http://127.0.0.1:8099/v1")
        val answer = discovery.discover("test", local, env)
        assertTrue(answer is Discovery.Unavailable)
        assertNull((answer as Discovery.Unavailable).url)
        assertTrue(credentialReads.isEmpty(), "a local runtime's list names a file, so nothing is asked of it")
    }

    @Test
    fun `the kept list round-trips, and only for the url that produced it`(@TempDir tmp: Path) {
        val cache = RosterCache(StatePaths(baseOverride = tmp))
        val models = listOf(DiscoveredModel("m-chat", "Chat", 256_000, listOf("m-chat-latest")), DiscoveredModel("m-2"))
        assertEquals(KeptRoster.None, cache.read("test", remote), "nothing kept yet")
        cache.write("test", Discovery.Found(LIST_URL, models))
        assertEquals(KeptRoster.Kept(models), cache.read("test", remote))
        // A moved endpoint is a different endpoint: its old answer says nothing about the new one.
        val moved = cache.read("test", remote.copy(modelsUrl = "https://elsewhere.test/models"))
        assertEquals(KeptRoster.OtherUrl(LIST_URL), moved, "and the line names the endpoint the kept list came from")
        assertEquals(KeptRoster.None, cache.read("other-head", remote), "each head keeps its own list")
    }

    // 2026-09-23 (review): this read as "nothing kept", and daemon.log said the same for a missing file,
    // a moved endpoint and a corrupt one — three causes with three different fixes.
    @Test
    fun `a kept list that cannot be read says so, and names the file`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp)
        Files.createDirectories(paths.modelRosterFile("test").parent)
        Files.writeString(paths.modelRosterFile("test"), "{not json")
        val kept = RosterCache(paths).read("test", remote)
        assertTrue(kept is KeptRoster.Unreadable, "$kept")
        val reason = (kept as KeptRoster.Unreadable).reason
        assertTrue(reason.contains(paths.modelRosterFile("test").toString()), reason)
        assertFalse(reason.contains("not json"), "a parse error's text can quote the file, so it is withheld")
    }
}
