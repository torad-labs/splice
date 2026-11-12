// PUT /api/topology refuses a changed system_prompt_file a turn could not read, in words naming why, and
// splice.toml is byte-identical after it. Marlin's walk of V4-348 on bb54736ea:
// the preview showed a refusal, Save wrote `system_prompt_file = "<missing path>"` anyway and dropped the
// head's system_prompt. The real loader parses every candidate, so a refusal here is the route's, never a
// stub's.
package splice.configuration.topology

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val HEAD = "ex"
private const val PATH = "heads.$HEAD.system_prompt_file"
private const val SYSTEM_PROMPT = "system_prompt"
private const val SYSTEM_PROMPT_FILE = "system_prompt_file"
private const val REGULAR = "instruction file must be a regular file"

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
    system_prompt = "keep this standing prompt"
""".trimIndent() + "\n"

class InstructionWriteCheckTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml") }
    private val backups by lazy { tmp.resolve("backups") }

    private fun routes(text: String): TopologyRoutes {
        Files.writeString(file, text)
        val writer = TopologyWriter(file, backups, TopologyParse(TopologyLoader::parse))
        return TopologyRoutes(TopologyWriterSource { writer }, TopologyStale { false })
    }

    /** The GET's topology with the head edited by [edit], as a PUT body. */
    private fun body(routes: TopologyRoutes, edit: (JsonObject) -> JsonObject): String {
        val read = Json.parseToJsonElement(routes.read().body).jsonObject.getValue("topology").jsonObject
        val heads = read.getValue("heads").jsonObject
        val edited = JsonObject(heads + (HEAD to edit(heads.getValue(HEAD).jsonObject)))
        return buildJsonObject { put("topology", JsonObject(read + ("heads" to edited))) }.toString()
    }

    private fun pointedAt(name: String, mode: String? = null): (JsonObject) -> JsonObject = { head ->
        val without = JsonObject(head - SYSTEM_PROMPT)
        val moded = mode?.let { JsonObject(without + ("system_prompt_mode" to JsonPrimitive(it))) } ?: without
        JsonObject(moded + (SYSTEM_PROMPT_FILE to JsonPrimitive(name)))
    }

    private fun assertRefused(name: String, sentence: String, mode: String = "append") {
        val routes = routes(BASE)
        val before = Files.readString(file)
        val reply = routes.write(body(routes, pointedAt(name, mode.takeIf { it != "append" })))
        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        val answer = Json.parseToJsonElement(reply.body).jsonObject
        assertFalse(answer.getValue("ok").jsonPrimitive.boolean, "$name: ${reply.body}")
        val findings = answer.getValue("findings").jsonArray.map { it.jsonObject }
        assertEquals(listOf(PATH), findings.map { it.getValue("path").jsonPrimitive.content }, name)
        assertEquals(sentence, findings.single().getValue("message").jsonPrimitive.content, name)
        assertFalse(reply.body.contains(tmp.toString()), "a refusal must not expose an absolute path")
        assertEquals(before, Files.readString(file), "$name: a refused write leaves splice.toml byte-identical")
        assertFalse(Files.exists(backups), "$name: a refused write takes no backup")
    }

    @Test
    fun `a missing instruction file is refused and says it does not exist`() {
        assertRefused("gone.md", "instruction file does not exist")
    }

    @Test
    fun `a directory is refused as not a regular file`() {
        Files.createDirectory(tmp.resolve("instructions"))
        assertRefused("instructions", REGULAR)
    }

    @Test
    fun `a symlink is refused as not a regular file`() {
        Files.writeString(tmp.resolve("real.md"), "Real instructions.\n")
        Files.createSymbolicLink(tmp.resolve("linked.md"), tmp.resolve("real.md"))
        assertRefused("linked.md", REGULAR)
    }

    @Test
    fun `a dangling symlink is refused as a link, not as absent`() {
        Files.createSymbolicLink(tmp.resolve("dangling.md"), tmp.resolve("nowhere.md"))
        assertRefused("dangling.md", REGULAR)
    }

    @Test
    fun `a non-UTF-8 file is refused as not UTF-8 text`() {
        Files.write(tmp.resolve("invalid.md"), byteArrayOf(0xC3.toByte(), 0x28))
        assertRefused("invalid.md", "instruction file is not UTF-8 text")
    }

    @Test
    fun `a binary file and an oversized file are refused, each in its own words`() {
        Files.write(tmp.resolve("binary.md"), byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))
        Files.write(tmp.resolve("large.md"), ByteArray(256 * 1024 + 1) { 'x'.code.toByte() })
        assertRefused("binary.md", "instruction file is not text")
        assertRefused("large.md", "instruction file exceeds the preview byte limit")
    }

    @Test
    fun `a strip file whose patterns do not compile is refused`() {
        Files.writeString(tmp.resolve("bad.strip"), "(unclosed\n")
        assertRefused("bad.strip", "instruction file or matching patterns cannot be read", mode = "strip")
    }

    @Test
    fun `a valid file writes and the inline prompt it replaces is gone`() {
        Files.writeString(tmp.resolve("good.md"), "Good instructions.\n")
        val routes = routes(BASE)
        val reply = routes.write(body(routes, pointedAt("good.md")))
        val answer = Json.parseToJsonElement(reply.body).jsonObject
        assertTrue(answer.getValue("ok").jsonPrimitive.boolean, reply.body)
        val written = Files.readString(file)
        assertTrue(written.contains("system_prompt_file = \"good.md\""), written)
        assertFalse(written.contains("keep this standing prompt"), written)
    }

    @Test
    fun `an already-stored unchanged file that is now missing does not block an unrelated write`() {
        val stored = BASE.replace("system_prompt = \"keep this standing prompt\"", "system_prompt_file = \"gone.md\"")
        val routes = routes(stored)
        val reply = routes.write(body(routes) { JsonObject(it + ("port" to JsonPrimitive(8802))) })
        val answer = Json.parseToJsonElement(reply.body).jsonObject
        assertTrue(answer.getValue("ok").jsonPrimitive.boolean, reply.body)
        val written = Files.readString(file)
        assertTrue(written.contains("port = 8802"), written)
        assertTrue(written.contains("system_prompt_file = \"gone.md\""), written)
    }

    @Test
    fun `changing the file of a head that already stored one is checked`() {
        val stored = BASE.replace("system_prompt = \"keep this standing prompt\"", "system_prompt_file = \"old.md\"")
        val routes = routes(stored)
        val reply = routes.write(body(routes, pointedAt("gone.md")))
        val answer = Json.parseToJsonElement(reply.body).jsonObject
        assertFalse(answer.getValue("ok").jsonPrimitive.boolean, reply.body)
        assertEquals(stored, Files.readString(file))
    }
}
