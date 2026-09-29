// NEW: V4-413 — a PUT /api/topology that wrote nothing says restart_required false. Marlin's walk of V4-400 on
// 3839fcc06: a refused write (missing instruction file) answered ok false with splice.toml untouched and
// restart_required true, because every refusal path built its Attempt with the default. One test per way
// a PUT is refused, and a write that moves a boot-only key still says true.
package splice.configuration.topology.v4413

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.configuration.topology.TopologyRoutes
import splice.configuration.topology.TopologyStale
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "ex"
private const val PROVIDER = "ex"
private const val OK = "ok"
private const val RESTART = "restart_required"

private val BASE = """
    [providers.ex]
    dialect = "openai-chat"
    base_url = "https://api.example.com/v1"
    auth = { kind = "api-key", env = "EX_KEY" }

    [[providers.ex.models]]
    id = "m1"
    context_window = 128000

    [heads.ex]
    provider = "ex"
    port = 8801
    discovery_prefix = "ex/"
    pinned_model = "m1"
""".trimIndent() + "\n"

class RefusedWriteRestartTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml") }

    private fun routes(): TopologyRoutes {
        Files.writeString(file, BASE)
        val writer = TopologyWriter(file, tmp.resolve("backups"), TopologyParse(TopologyLoader::parse))
        return TopologyRoutes(TopologyWriterSource { writer }, TopologyStale { false })
    }

    private fun read(routes: TopologyRoutes): JsonObject =
        Json.parseToJsonElement(routes.read().body).jsonObject.getValue("topology").jsonObject

    /** The GET's topology with [key] set to [value] on the [name] entry of [table], as a PUT body. */
    private fun body(routes: TopologyRoutes, table: String, name: String, key: String, value: JsonElement): String {
        val topology = read(routes)
        val tables = topology.getValue(table).jsonObject
        val entry = JsonObject(tables.getValue(name).jsonObject + (key to value))
        val edited = JsonObject(tables + (name to entry))
        return buildJsonObject { put("topology", JsonObject(topology + (table to edited))) }.toString()
    }

    private fun port(routes: TopologyRoutes, number: JsonElement): String = body(routes, "heads", HEAD, "port", number)

    private fun put(routes: TopologyRoutes, body: String): JsonObject {
        val reply = routes.write(body)
        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        return Json.parseToJsonElement(reply.body).jsonObject
    }

    private fun assertRefusedWithoutRestart(answer: JsonObject, before: String, why: String) {
        assertFalse(answer.getValue(OK).jsonPrimitive.boolean, "$why: the write must be refused: $answer")
        assertFalse(answer.getValue(RESTART).jsonPrimitive.boolean, "$why: nothing was written, so no restart: $answer")
        assertEquals(before, Files.readString(file), "$why: a refused write leaves splice.toml byte-identical")
    }

    @Test
    fun `an unreadable instruction file is refused without a restart`() {
        val routes = routes()
        val before = Files.readString(file)
        val answer = put(routes, body(routes, "heads", HEAD, "system_prompt_file", JsonPrimitive("gone.md")))
        assertRefusedWithoutRestart(answer, before, "missing instruction file")
    }

    @Test
    fun `a masked header the file does not store is refused without a restart`() {
        val routes = routes()
        val before = Files.readString(file)
        val headers = JsonObject(mapOf("X-Key" to JsonPrimitive("********")))
        val answer = put(routes, body(routes, "providers", PROVIDER, "extra_headers", headers))
        assertRefusedWithoutRestart(answer, before, "masked header with nothing stored")
    }

    @Test
    fun `a topology that does not decode is refused without a restart`() {
        val routes = routes()
        val before = Files.readString(file)
        val answer = put(routes, port(routes, JsonPrimitive("not-a-port")))
        assertRefusedWithoutRestart(answer, before, "undecodable port")
    }

    @Test
    fun `a file that no longer parses is refused without a restart`() {
        val routes = routes()
        val request = port(routes, JsonPrimitive(8802))
        Files.writeString(file, "[[[ not toml")
        val answer = put(routes, request)
        assertRefusedWithoutRestart(answer, "[[[ not toml", "unparsable splice.toml")
    }

    @Test
    fun `a write that moves a boot-only key still says a restart is required`() {
        val routes = routes()
        val answer = put(routes, port(routes, JsonPrimitive(8802)))
        assertTrue(answer.getValue(OK).jsonPrimitive.boolean, answer.toString())
        assertTrue(answer.getValue(RESTART).jsonPrimitive.boolean, "a moved port is boot-only: $answer")
        assertTrue(Files.readString(file).contains("8802"), "the write reached splice.toml")
    }
}
