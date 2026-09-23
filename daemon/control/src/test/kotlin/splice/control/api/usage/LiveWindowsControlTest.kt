// NEW: V4-162 — the control plane's three window surfaces, each against the seam it reads:
//
//   LaunchSpec.withWindows   a launch plants the window in force NOW, and each picker row in the
//                            model-options cache takes its row's window; a spec whose windows did
//                            not move comes back equal, so a launch without an edit is unchanged;
//   PUT /api/topology        restart_required is false only for a write that moved nothing but
//                            context windows (TopologyRoutes, through the real TopologyWriter);
//   /health                  topologyDigest is read per request, so it follows the version the
//                            daemon runs rather than the one it booted.
package splice.control.api.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudePolicy
import splice.control.HeadTrees
import splice.control.LaunchSpec
import splice.control.TopologyDigest
import splice.control.TopologyStale
import splice.control.api.ControlPayloads
import splice.control.api.fleet.TopologyRoutes
import splice.core.model.LiveWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import java.nio.file.Files
import java.nio.file.Path

private const val FILE = """[providers.ex]
dialect = "openai-chat"
base_url = "https://api.example.com/v1"

[[providers.ex.models]]
id = "m1"
context_window = 128000

[[providers.ex.models]]
id = "m2"
context_window = 64000

[heads.ex]
provider = "ex"
port = 8801
discovery_prefix = "ex/"
pinned_model = "m1"
"""

class LiveWindowsControlTest {

    @TempDir
    lateinit var tmp: Path

    private fun catalog(m1: Long, m2: Long = 64_000, live: ModelCatalog? = null) = ModelCatalog(
        discoveryPrefix = "ex/",
        models = listOf(ModelEntry("m1", contextWindow = m1), ModelEntry("m2", contextWindow = m2)),
        defaultContextWindow = 0,
        pinnedModel = "m1",
        liveWindows = live?.let { LiveWindows { it } },
    )

    /** Shaped as HeadBuildInputs.modelOptionsCache builds it at boot, plus a row that is no row. */
    private val cache = buildJsonArray {
        add(option("m1", "M1", 128_000))
        add(option("m2", "M2", 64_000))
        add(JsonPrimitive("not a row"))
    }

    private fun option(id: String, label: String, window: Long): JsonObject = buildJsonObject {
        put("value", id)
        put("label", label)
        put("context_window", window)
    }

    private fun spec() = LaunchSpec(
        trees = HeadTrees(tmp.resolve(".claude-ex")),
        pinnedModel = "m1",
        availableModelIds = listOf("m1", "m2"),
        modelLabels = mapOf("m1" to "M1", "m2" to "M2"),
        contextWindow = 128_000,
        modelOptionsCache = cache,
        statuslineCommand = "",
        loginCommand = "",
        signInLabel = "Ex",
        policy = ClaudePolicy(share = emptySet(), isolate = emptySet()),
        port = 8801,
        inferenceToken = "t",
        apiTimeoutMs = 960_000,
    )

    @Test
    fun `a launch plants the window in force and each picker row takes its own`() {
        val launched = spec().withWindows(catalog(128_000, live = catalog(245_760, m2 = 98_304)))

        assertEquals(245_760, launched.contextWindow)
        val rows = launched.modelOptionsCache as JsonArray
        assertEquals(245_760, rows[0].jsonObject.getValue("context_window").jsonPrimitive.content.toLong())
        assertEquals(98_304, rows[1].jsonObject.getValue("context_window").jsonPrimitive.content.toLong())
        assertEquals("M2", rows[1].jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals(JsonPrimitive("not a row"), rows[2])
    }

    @Test
    fun `a launch with no edit is the boot spec`() {
        val boot = spec()

        assertEquals(boot, boot.withWindows(catalog(128_000)))
        assertEquals(boot, boot.withWindows(catalog(128_000, live = catalog(128_000))))
    }

    // --- PUT /api/topology ----------------------------------------------------------------------

    private fun topology(m1: Long = 128_000, port: Int = 8801) = Topology(
        providers = mapOf(
            "ex" to ProviderConfig(
                Dialect.OPENAI_CHAT,
                "https://api.example.com/v1",
                AuthConfig("api-key", env = "EX_KEY"),
                models = listOf(ModelEntry("m1", contextWindow = m1), ModelEntry("m2", contextWindow = 64_000)),
            ),
        ),
        heads = mapOf("ex" to HeadConfig("ex", port, "ex/", "m1")),
    )

    /** control has no TOML parser: this one reads the two numbers the tests edit and nothing else. */
    private val parse = TopologyParse { text ->
        val m1 = Regex("id = \"m1\"\ncontext_window = (\\d+)").find(text)!!.groupValues[1].toLong()
        val port = Regex("port = (\\d+)").find(text)!!.groupValues[1].toInt()
        topology(m1, port)
    }

    private fun put(requested: Topology): JsonObject {
        val file = tmp.resolve("splice.toml")
        if (!Files.exists(file)) Files.writeString(file, FILE)
        val writer = TopologyWriter(file, parse)
        val routes = TopologyRoutes(TopologyWriterSource { writer }, TopologyStale { false })
        val body = buildJsonObject { put("topology", writer.tree(requested)) }.toString()
        return Json.parseToJsonElement(routes.write(body).body).jsonObject
    }

    @Test
    fun `a write that moves only a context window needs no restart`() {
        val reply = put(topology(m1 = 245_760))

        assertTrue(reply.getValue("ok").jsonPrimitive.boolean, reply.toString())
        assertFalse(reply.getValue("restart_required").jsonPrimitive.boolean)
        assertTrue("context_window = 245760" in Files.readString(tmp.resolve("splice.toml")))
    }

    @Test
    fun `a write that moves anything else still needs a restart`() {
        val reply = put(topology(m1 = 245_760, port = 8802))

        assertTrue(reply.getValue("ok").jsonPrimitive.boolean, reply.toString())
        assertTrue(reply.getValue("restart_required").jsonPrimitive.boolean)
    }

    // --- /health --------------------------------------------------------------------------------

    @Test
    fun `health reads the running digest per request`() {
        var running = "boot"
        val payloads = ControlPayloads(
            heads = emptyMap(),
            failedHeads = { 0 },
            configuredHeads = 0,
            topologyDigest = TopologyDigest { running },
        )
        fun digest() = Json.parseToJsonElement(payloads.controlHealthJson()).jsonObject
            .getValue("topologyDigest").jsonPrimitive.content

        assertEquals("boot", digest())
        running = "edited"
        assertEquals("edited", digest())
    }
}
