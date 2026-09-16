// NEW: V4-35 — DeepSeek over its Anthropic-format endpoint. The failure this profile can cause is
// a head that refuses to BOOT, and nothing in the profile source reveals it on inspection, so the
// tests build the head rather than reading the fields back.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TopologyLoader
import splice.app.cli.AddProfiles
import splice.core.topology.Dialect
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DeepSeekProfileTest {

    private val profile = requireNotNull(AddProfiles().find("deepseek")) { "deepseek profile missing" }

    @Test
    fun `the emitted topology actually loads`(@TempDir dir: Path) {
        // Slots are flat-mapped to one row per slot, so two slots on one id emit two rows with the
        // same id and modelsFor throws "head model list contains duplicates". A profile can look
        // perfectly reasonable in source and still produce a head that cannot boot.
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "deepseek", PORT))
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["deepseek"]) { "deepseek head absent after load" }
        assertEquals("claude-deepseek", head.claude.command)
        // catalogFor is where modelsFor runs, and modelsFor is where the duplicate-id and
        // unknown-slot rules live. Loading alone does NOT reach them — measured, not assumed: a
        // mutation giving one id two slots left this test green until this line existed. Parsing a
        // topology and BUILDING a head from it are different claims and only the second is the one
        // an operator experiences at daemon boot.
        val provider = requireNotNull(topology.providers["deepseek"]) { "deepseek provider absent" }
        val catalog = provider.catalogFor(head)
        assertEquals(2, catalog.models.size, "both declared rows must resolve")
        assertEquals("deepseek-flash", catalog.pinnedModel)
    }

    @Test
    fun `no model id carries two slots`() {
        val mappings = profile.models.flatMap { model -> model.slots.map { slot -> model.id to slot } }
        val ids = mappings.map { it.first }
        assertEquals(ids.distinct().size, ids.size, "an id repeated across slots cannot load: $mappings")
        val slots = mappings.map { it.second }
        assertEquals(slots.distinct().size, slots.size, "a slot claimed twice cannot load: $mappings")
    }

    @Test
    fun `flash takes opus because the vendor's own evidence outranks the model name`() {
        // DeepSeek document that V4.1 Flash surpassed V4 Pro on performance, cost AND speed. If a
        // later revision flips that, this assertion is the thing that should be argued with.
        val opus = profile.models.single { "opus" in it.slots }
        assertEquals("deepseek-flash", opus.id)
    }

    @Test
    fun `the allowlist drops every block DeepSeek reject, redacted_thinking above all`() {
        val quirks = profile.providerExtra.single { it.startsWith("block_allowlist") }
        // DR-118 forwards redacted_thinking verbatim because Anthropic requires it back unchanged.
        // On this endpoint that block is rejected, so it must not survive into the request.
        for (rejected in REJECTED) {
            assertFalse(rejected in quirks, "DeepSeek reject '$rejected' — it must not be allowlisted: $quirks")
        }
        for (supported in SUPPORTED) {
            assertTrue(supported in quirks, "'$supported' is supported and must ride: $quirks")
        }
    }

    @Test
    fun `cache_control is stripped and the wire is the anthropic-format endpoint`() {
        assertTrue(profile.providerExtra.any { it == "strip_cache_control = true" })
        assertEquals("https://api.deepseek.com/anthropic", profile.baseUrl)
        assertEquals("anthropic-passthrough", profile.dialect)
        // No toolNameCap: DeepSeek document tool `name` as fully supported with no length limit,
        // unlike Muse. If a live turn ever 400s on a long name, that is the knob to reach for.
        assertEquals("api-key", profile.authKind)
    }

    /** The reasoning_cache scar in ExampleConfigTest's roster test is exactly this assertion's reason for
     *  existing, and it applies harder here: block_allowlist and strip_cache_control ARE the
     *  DeepSeek integration. If either were decorative — parsed and then ignored — every replayed
     *  signed-thinking turn would still ship a redacted_thinking block DeepSeek reject, and the
     *  head would fail in a way no unit test of the profile source could see. */
    @Test
    fun `deepseek quirks reach the parsed fields rather than decorating the example`() {
        val topology = TopologyLoader.parse(exampleToml())
        val deepseek = topology.providers[topology.heads["claude-deepseek"]!!.provider]!!
        assertEquals(Dialect.ANTHROPIC_PASSTHROUGH, deepseek.dialect)
        assertEquals("api-key", deepseek.auth.kind)
        assertEquals(true, deepseek.quirks.stripCacheControl)
        val allowed = deepseek.quirks.blockAllowlist!!
        assertTrue("thinking" in allowed, "thinking is supported and must ride")
        assertTrue("redacted_thinking" !in allowed, "DeepSeek reject redacted_thinking")
    }

    /** Same walk ExampleConfigTest does: from the gateway module dir up to the repo root. The
     *  committed example is a TESTED artifact, so this reads the real file rather than a fixture. */
    private fun exampleToml(): String {
        var dir = Paths.get("").toAbsolutePath()
        repeat(WALK_UP) {
            val candidate = dir.resolve("config").resolve("splice.example.toml")
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent ?: return@repeat
        }
        error("config/splice.example.toml not found from " + Paths.get("").toAbsolutePath())
    }

    @Test
    fun `the api-key env is the derived DEEPSEEK_API_KEY`() {
        assertEquals("DEEPSEEK_API_KEY", AddProfiles().apiKeyEnv("deepseek"))
        assertNotNull(AddProfiles().find("deepseek"))
    }
}

private const val PORT = 3107
private const val WALK_UP = 4
private val REJECTED = listOf(
    "redacted_thinking",
    "image",
    "document",
    "search_result",
    "mcp_tool_use",
    "mcp_tool_result",
    "container_upload",
    "code_execution_tool_result",
)
private val SUPPORTED = listOf(
    "text",
    "thinking",
    "tool_use",
    "tool_result",
    "web_search_tool_result",
)
private val DAEMON_BLOCK = """
    [daemon]
    control_port = 3096
""".trimIndent() + "\n"
