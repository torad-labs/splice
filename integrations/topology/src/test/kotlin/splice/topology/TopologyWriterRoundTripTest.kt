// NEW: V4-128 — TopologyWriter against the REAL loader, TopologyLoader.parse, the one the daemon boots
// with. A stub parser would pass the round-trip check trivially; this is the only place the writer's
// output is judged by ktoml itself.
//
// EVERY EDIT IS PINNED AS BYTES. Each test states the file before and the exact file after, and the
// requested topology is the loader's own reading of that expected file, so a patch that lands in the
// wrong table, leaves a removed key behind or reflows a comment fails here by name, even where the
// result would still parse. Refusals are pinned as byte-identity plus the absence of a backup.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

/** 2026-09-18T12:00:00Z. */
private const val NOW = 1_789_732_800_000L

private val BASE = """
    # splice topology: operator notes survive every write
    [daemon]
    effort = "high" # keep me
    summary = "auto"

    [providers.ex]
    dialect = "openai-chat"
    base_url = "https://api.example.com/v1"
    auth = { kind = "api-key", env = "EX_KEY" }
    extra_headers = { x-api-key = "sk-secret" }

    # the model rows
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
    models = [
      { id = "m1", slot = "opus" },
    ]

    [heads.ex.claude]
    command = "claude-ex"
""".trimIndent() + "\n"

class TopologyWriterRoundTripTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, BASE) } }
    private val writer by lazy { TopologyWriter(file, TopologyParse(TopologyLoader::parse), WallClock { NOW }) }

    /** Writes the topology [expected] declares and asserts the file became exactly [expected]. */
    private fun lands(expected: String) {
        val result = writer.write(TopologyLoader.parse(expected))
        assertTrue(result is TopologyWriteResult.Written, "refused: $result")
        assertEquals(expected, Files.readString(file))
        val backup = (result as TopologyWriteResult.Written).backup
        assertEquals(BASE, backup?.let(Files::readString), "the backup holds the file as it was before the write")
    }

    /** A second head on provider `ex`, pinned to m2, as the section the writer appends. */
    private fun head(key: String, port: Int): String =
        "\n[heads.$key]\nprovider = \"ex\"\nport = $port\ndiscovery_prefix = \"$key/\"\npinned_model = \"m2\"\n"

    private fun backups(): List<String> = Files.list(tmp).use { paths ->
        paths.map { it.fileName.toString() }.filter { it.contains(".bak-") }.toList()
    }

    @Test
    fun `a changed value is rewritten in place and its comment survives`() {
        lands(BASE.replace("effort = \"high\" # keep me", "effort = \"low\" # keep me"))
    }

    @Test
    fun `a changed number keeps the spacing and comment after it`() {
        val spaced = BASE.replace("port = 8801\n", "port = 8801   # the head's port\n")
        Files.writeString(file, spaced)
        val expected = spaced.replace("port = 8801 ", "port = 8802 ")
        assertTrue(writer.write(TopologyLoader.parse(expected)) is TopologyWriteResult.Written)
        assertEquals(expected, Files.readString(file))
    }

    @Test
    fun `an added key lands after the last key of its own table`() {
        lands(BASE.replace("summary = \"auto\"\n", "summary = \"auto\"\nshow_reasoning = \"raw\"\n"))
    }

    @Test
    fun `a removed key leaves with its line and nothing else moves`() {
        lands(BASE.replace("summary = \"auto\"\n", ""))
    }

    @Test
    fun `an added table is appended as its own section`() {
        lands(BASE + head("ex2", 8802))
    }

    @Test
    fun `a removed table leaves with its sub-tables and the comments before it stay`() {
        lands(BASE.substringBefore("[heads.ex]"))
    }

    @Test
    fun `a changed array of tables is rewritten as sections where the first one stood`() {
        val rows = "[[providers.ex.models]]\nid = \"m1\"\ncontext_window = 128000\n\n" +
            "[[providers.ex.models]]\nid = \"m2\"\ncontext_window = 32000\n"
        val before = BASE.substringBefore("[[providers.ex.models]]")
        lands(before + rows + "\n" + BASE.substringAfter("context_window = 64000\n\n"))
    }

    @Test
    fun `a key inside an inline table is written by rewriting that inline table`() {
        lands(BASE.replace("{ x-api-key = \"sk-secret\" }", "{ x-api-key = \"sk-new\", anthropic-version = \"1\" }"))
    }

    @Test
    fun `removing the last header deletes the inline table's line`() {
        lands(BASE.replace("extra_headers = { x-api-key = \"sk-secret\" }\n", ""))
    }

    @Test
    fun `a key added beside dotted siblings is written dotted beside them`() {
        val dotted = BASE.replace("auth = {", "quirks.store = true\nauth = {")
        Files.writeString(file, dotted)
        val expected = dotted.replace("quirks.store = true\n", "quirks.store = true\nquirks.tool_choice = true\n")
        assertTrue(writer.write(TopologyLoader.parse(expected)) is TopologyWriteResult.Written)
        assertEquals(expected, Files.readString(file))
    }

    @Test
    fun `a table the file spells with only default values is written into, not appended twice`() {
        val spelled = BASE + "\n[providers.ex.quirks]\nstore = false\n"
        Files.writeString(file, spelled)
        val expected = spelled + "tool_choice = true\n"
        assertTrue(writer.write(TopologyLoader.parse(expected)) is TopologyWriteResult.Written)
        assertEquals(expected, Files.readString(file))
    }

    @Test
    fun `brackets inside strings and comments do not end an array early`() {
        val tricky = BASE.replace(
            "summary = \"auto\"\n",
            "summary = \"auto\"\nfold_reasoning_models = [\n  \"a]b\", # ] not the end\n  \"c\",\n] # after\n",
        )
        Files.writeString(file, tricky)
        val expected = tricky.replace("[\n  \"a]b\", # ] not the end\n  \"c\",\n]", "[\"z\"]")
        assertTrue(writer.write(TopologyLoader.parse(expected)) is TopologyWriteResult.Written)
        assertEquals(expected, Files.readString(file))
    }

    @Test
    fun `a new sub-table of an existing table is appended as a section`() {
        lands(BASE + "\n[providers.ex.quirks]\nstore = true\n")
    }

    @Test
    fun `a project key that is a path is quoted and read back as the same key`() {
        lands(BASE + "\n[projects.\"/home/op/repo\"]\nsystem_prompt = \"be terse\"\n")
    }

    @Test
    fun `quotes, backslashes and line breaks in a value survive the loader`() {
        val roster = "  { id = \"m1\", slot = \"opus\" },\n]\n"
        lands(BASE.replace(roster, roster + "system_prompt = \"say \\\"hi\\\"\\n\\\\ok\"\n"))
        assertEquals("say \"hi\"\n\\ok", TopologyLoader.parse(Files.readString(file)).heads.getValue("ex").systemPrompt)
    }

    @Test
    fun `a request equal to the file writes nothing and takes no backup`() {
        assertEquals(TopologyWriteResult.Written(null), writer.write(TopologyLoader.parse(BASE)))
        assertEquals(BASE, Files.readString(file))
        assertEquals(emptyList<String>(), backups())
    }

    @Test
    fun `a refused write leaves the file byte-identical and takes no backup`() {
        val clash = TopologyLoader.parse(BASE + head("ex2", 8801))
        val result = writer.write(clash)
        assertTrue(result is TopologyWriteResult.Refused, "a shared port is refused: $result")
        assertEquals(BASE, Files.readString(file))
        assertEquals(emptyList<String>(), backups())
    }

    @Test
    fun `the backup is named for the second and the old bytes, and a second write keeps both`() {
        lands(BASE.replace("\"high\"", "\"low\""))
        val next = Files.readString(file).replace("\"low\"", "\"medium\"")
        writer.write(TopologyLoader.parse(next))
        val names = backups().sorted()
        assertEquals(2, names.size, "two writes, two backups: $names")
        names.forEach { assertTrue(it.startsWith("splice.toml.bak-20260918T120000Z-"), it) }
    }
}
