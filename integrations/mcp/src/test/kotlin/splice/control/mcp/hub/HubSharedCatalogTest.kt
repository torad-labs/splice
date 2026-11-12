package splice.control.mcp.hub

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpServerInput
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpSharing
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpHandshakeBudget
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class HubSharedCatalogTest {
    @TempDir
    lateinit var directory: Path

    private val codec = JsonRpcCodec()
    private val entry = buildJsonObject { put("command", "catalog-fixture") }
    private val spec = McpServerSpec("shared", "catalog-fixture", emptyList(), emptyMap())
    private val catalog = checkNotNull(
        codec.parse(
            """{"tools":[{"name":"raw.tool","title":"Raw title","description":"Exact description", """ +
                """"inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},"_meta":{"fixture":true}}],""" +
                """"_meta":{"retained":true}}""",
        ),
    )

    @Test
    fun `public snapshot preserves the raw listing and follows every declared tuple component`() {
        val hub = hub()
        keep(hub, catalog)
        assertEquals(mapOf("shared" to catalog), hub.sharedTools(mapOf("shared" to spec)))
        val changed = listOf(
            spec.copy(command = "other-fixture"),
            spec.copy(args = listOf("changed")),
            spec.copy(env = mapOf("MODE" to "changed")),
            spec.copy(cwd = directory.toString()),
        )
        changed.forEach { assertTrue(hub.sharedTools(mapOf("shared" to it)).isEmpty()) }
        assertEquals(
            mapOf("alias" to catalog),
            hub.sharedTools(mapOf("alias" to spec.copy(name = "alias"))),
            "a current declared alias may observe the identical launch tuple",
        )
    }

    @Test
    fun `a paginated or malformed retained listing supplies no complete catalog`() {
        val hub = hub()
        val tools = checkNotNull(catalog["tools"] as? JsonArray)
        val malformed = listOf(
            JsonObject(catalog + ("nextCursor" to JsonPrimitive("page-two"))),
            JsonObject(catalog + ("nextCursor" to JsonPrimitive(""))),
            buildJsonObject { put("tools", "not an array") },
            buildJsonObject { put("tools", JsonArray(listOf(JsonPrimitive("not a tool")))) },
            buildJsonObject {
                put(
                    "tools",
                    JsonArray(listOf(buildJsonObject { put("name", 7); put("inputSchema", JsonObject(emptyMap())) })),
                )
            },
            buildJsonObject { put("tools", JsonArray(listOf(buildJsonObject { put("name", "missing-schema") }))) },
            buildJsonObject { put("tools", JsonArray(tools + tools)) },
        )
        malformed.forEach {
            keep(hub, it)
            assertTrue(hub.sharedTools(mapOf("shared" to spec)).isEmpty(), it.toString())
        }
        val complete = JsonObject(catalog + ("nextCursor" to JsonNull))
        keep(hub, complete)
        assertEquals(complete, hub.sharedTools(mapOf("shared" to spec))["shared"])
        val resolved = checkNotNull(hub.resolve("""{"server":"shared"}""").body?.let(codec::parse))
        val listing = Path.of(checkNotNull(JsonScalars.str(resolved, "listing")))
        Files.writeString(listing, "incomplete-json")
        assertTrue(hub.sharedTools(mapOf("shared" to spec)).isEmpty())
    }

    private fun hub(): McpHub = McpHub(
        McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "fixture" }, DirectoryProbe { false }),
        McpLaunchServers { _, _ -> listOf(McpServerInput("shared", entry)) },
        McpLaunchPlaces { null },
        McpHubPaths(directory, directory.resolve("unused-holder"), directory),
        McpHandshakeBudget { 1.seconds },
        Dispatchers.Unconfined,
        LogSink {},
    )

    private fun keep(hub: McpHub, result: JsonObject) {
        val request = buildJsonObject {
            put("server", "shared")
            put(
                "initialize",
                buildJsonObject {
                    put("serverInfo", buildJsonObject { put("name", "fixture"); put("version", "1") })
                },
            )
            put("tools", result)
        }
        assertEquals(204, hub.listing(codec.encode(request)).status)
    }
}
