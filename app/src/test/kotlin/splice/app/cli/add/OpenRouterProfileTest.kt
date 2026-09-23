// NEW: V4-34 — OpenRouter ships a real model surface. Loading the topology is not enough:
// catalogFor is where modelsFor runs, and that is where duplicate-id and unknown-slot refuse boot.
package splice.app.cli.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class OpenRouterProfileTest {

    private val profile = requireNotNull(AddProfiles().find("openrouter")) { "openrouter profile missing" }

    @Test
    fun `the emitted topology actually loads`(@TempDir dir: Path) {
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "openrouter", PORT))
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["openrouter"]) { "openrouter head absent after load" }
        assertEquals("claude-openrouter", head.claude.command)
        val provider = requireNotNull(topology.providers["openrouter"]) { "openrouter provider absent" }
        assertEquals(PROFILE_MODELS, provider.models.size, "every declared row must parse")
        val catalog = provider.catalogFor(head)
        assertEquals(SLOTTED, catalog.models.size, "the four slotted rows are the boot catalog")
        assertEquals("anthropic/claude-sonnet-5", catalog.pinnedModel)
    }

    @Test
    fun `no model id carries two slots`() {
        val mappings = profile.models.flatMap { model -> model.slots.map { slot -> model.id to slot } }
        val ids = mappings.map { it.first }
        assertEquals(ids.distinct().size, ids.size, "an id repeated across slots cannot load: $mappings")
        val slots = mappings.map { it.second }
        assertEquals(slots.distinct().size, slots.size, "a slot claimed twice cannot load: $mappings")
        assertEquals(listOf("sonnet", "opus", "haiku", "fable"), slots)
    }

    @Test
    fun `the starter catalog carries ten models and four unique slots`(@TempDir dir: Path) {
        val path = dir.resolve("splice.toml")
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["openrouter"]) { "starter head missing" }
        assertEquals("claude-openrouter", head.claude.command)
        val provider = requireNotNull(topology.providers["openrouter"]) { "starter provider missing" }
        assertEquals(PROFILE_MODELS, provider.models.size)
        val catalog = provider.catalogFor(head)
        assertEquals(SLOTTED, catalog.models.size)
        assertEquals("anthropic/claude-sonnet-5", catalog.pinnedModel)
        val slots = head.models.orEmpty().map { it.slot }
        assertEquals(listOf("sonnet", "opus", "haiku", "fable"), slots)
        assertEquals(slots.distinct().size, slots.size)
        assertTrue(Files.readString(path).contains("command = \"claude-openrouter\""))
    }

    @Test
    fun `the profile ships ten rows and the openrouter wrapper command`() {
        assertEquals(PROFILE_MODELS, profile.models.size)
        assertEquals("claude-openrouter", profile.command)
        assertEquals("openai-chat", profile.dialect)
        assertEquals("api-key", profile.authKind)
        assertEquals("https://openrouter.ai/api/v1", profile.baseUrl)
        assertEquals("OPENROUTER_API_KEY", AddProfiles().apiKeyEnv("openrouter"))
    }
}

private const val PORT = 3101
private const val PROFILE_MODELS = 10
private const val SLOTTED = 4
private val DAEMON_BLOCK = """
    [daemon]
    control_port = 3096
""".trimIndent() + "\n"
