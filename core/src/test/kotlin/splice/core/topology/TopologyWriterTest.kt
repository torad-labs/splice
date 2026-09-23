// NEW: V4-128 — the TopologyWriter arms that do not need a TOML parser: the boot checks it adds, and
// every refusal, each pinned as a byte-identical file with no backup taken. The parser here is a
// lookup table from exact text to topology, so a composed file the test did not predict byte for byte
// is "unparseable" and refused. What the loader itself says about the writer's output is
// app/console/v4128/TopologyWriterRoundTripTest's job, against the real TopologyLoader.parse.
package splice.core.topology

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.DiscoveredModel
import splice.core.model.ModelEntry
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

/** 2026-09-18T12:00:00Z. */
private const val NOW = 1_789_732_800_000L
private const val PORT = 8801
private const val WINDOW = 128_000L
private const val FILE = "[heads.ex]\nport = 8801 # the head\n"
private const val EDITED = "[heads.ex]\nport = 8802 # the head\n"

class TopologyWriterTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, FILE) } }

    private fun topology(port: Int = PORT, head: HeadConfig = head(port)): Topology = Topology(
        providers = mapOf(
            "ex" to ProviderConfig(
                Dialect.OPENAI_CHAT,
                "https://api.example.com/v1",
                AuthConfig("api-key", env = "EX_KEY"),
                models = listOf(ModelEntry("m1", contextWindow = WINDOW)),
            ),
        ),
        heads = mapOf("ex" to head),
    )

    private fun head(port: Int): HeadConfig = HeadConfig("ex", port, "ex/", "m1")

    private fun writer(table: Map<String, Topology>, parse: TopologyParse? = null): TopologyWriter = TopologyWriter(
        file,
        parse ?: TopologyParse { text -> table[text] ?: throw IllegalArgumentException("not a text this test predicted") },
        WallClock { NOW },
    )

    private fun backups(): List<String> = Files.list(tmp).use { paths ->
        paths.map { it.fileName.toString() }.filter { it != "splice.toml" }.toList()
    }

    /** The refusal left the file exactly as it was and wrote nothing beside it. */
    private fun untouched(result: TopologyWriteResult): List<TopologyFinding> {
        assertTrue(result is TopologyWriteResult.Refused, "expected a refusal: $result")
        assertEquals(FILE, Files.readString(file))
        assertEquals(emptyList<String>(), backups())
        return (result as TopologyWriteResult.Refused).findings
    }

    @Test
    fun `a changed value lands, with the old bytes in a dated backup`() {
        val result = writer(mapOf(FILE to topology(), EDITED to topology(port = 8802))).write(topology(port = 8802))
        assertEquals(EDITED, Files.readString(file))
        val backup = (result as TopologyWriteResult.Written).backup
        assertEquals("splice.toml.bak-20260918T120000Z-", backup?.fileName.toString().substringBeforeLast('-') + "-")
        assertEquals(FILE, backup?.let(Files::readString))
    }

    @Test
    fun `a head naming an undeclared provider is refused by name`() {
        val stray = topology(head = HeadConfig("nope", PORT, "ex/", "m1"))
        val findings = untouched(writer(mapOf(FILE to topology())).write(stray))
        assertEquals(listOf("heads.ex.provider"), findings.map { it.path })
    }

    @Test
    fun `a port outside the TCP range is refused by name`() {
        val findings = untouched(writer(mapOf(FILE to topology())).write(topology(port = 0)))
        assertEquals(TopologyFinding("heads.ex.port", "port 0 is outside 1-65535"), findings.single())
    }

    @Test
    fun `a head on the control plane's port is refused on both owners`() {
        val findings = untouched(writer(mapOf(FILE to topology())).write(topology(port = 3096)))
        assertEquals(listOf("heads.ex.port", "daemon.control_port"), findings.map { it.path })
    }

    @Test
    fun `a head roster the provider does not declare is refused by name`() {
        val roster = head(PORT).copy(models = listOf(HeadModel("m9", "opus")))
        val findings = untouched(writer(mapOf(FILE to topology())).write(topology(head = roster)))
        assertEquals(listOf("heads.ex.models"), findings.map { it.path })
    }

    // 2026-09-22: a model the head's endpoint served at start may be named by its allowlist, and the
    // console validates that edit exactly as boot will resolve it. The edit then stops at the parse
    // table — this test predicts no composed text — which is the proof the roster check let it by.
    @Test
    fun `a head roster naming a model its endpoint served is not refused as undeclared`() {
        val roster = head(PORT).copy(models = listOf(HeadModel("m1", "opus"), HeadModel("m9", "sonnet")))
        val served = TopologyWriter(
            file,
            TopologyParse { text -> topology().also { require(text == FILE) { "unpredicted" } } },
            WallClock { NOW },
            discovered = { key -> if (key == "ex") listOf(DiscoveredModel("m9")) else emptyList() },
        )
        val findings = untouched(served.write(topology(head = roster)))
        assertTrue(findings.none { it.path == "heads.ex.models" }, findings.toString())
        assertTrue(findings.single().message.startsWith("the edit would not parse"), findings.toString())
    }

    @Test
    fun `a file that does not parse is never edited`() {
        val findings = untouched(writer(emptyMap()).write(topology()))
        assertTrue(findings.single().message.startsWith("splice.toml on disk does not parse"), findings.toString())
    }

    @Test
    fun `an edit that would not parse is refused before any byte moves`() {
        val findings = untouched(writer(mapOf(FILE to topology())).write(topology(port = 8802)))
        assertTrue(findings.single().message.startsWith("the edit would not parse"), findings.toString())
    }

    @Test
    fun `an edit the loader reads back differently is refused at the key that drifted`() {
        val table = mapOf(FILE to topology(), EDITED to topology(port = 8803))
        val findings = untouched(writer(table).write(topology(port = 8802)))
        val drift = TopologyFinding("heads.ex.port", "the writer cannot express this edit; nothing was written")
        assertEquals(drift, findings.single())
    }

    @Test
    fun `a file changed while the write was prepared is left as the other writer made it`() {
        val other = "# someone else\n$FILE"
        val racing = TopologyParse { text ->
            if (text == EDITED) Files.writeString(file, other)
            mapOf(FILE to topology(), EDITED to topology(port = 8802)).getValue(text)
        }
        val result = writer(emptyMap(), racing).write(topology(port = 8802))
        assertTrue(result is TopologyWriteResult.Refused, result.toString())
        assertEquals(other, Files.readString(file))
        assertEquals(emptyList<String>(), backups(), "no backup and no temp file")
    }

    @Test
    fun `an existing backup of the same name is never overwritten`() {
        val first = writer(mapOf(FILE to topology(), EDITED to topology(port = 8802))).write(topology(port = 8802))
        val backup = checkNotNull((first as TopologyWriteResult.Written).backup)
        Files.writeString(backup, "kept")
        Files.writeString(file, FILE)
        writer(mapOf(FILE to topology(), EDITED to topology(port = 8802))).write(topology(port = 8802))
        assertEquals("kept", Files.readString(backup))
    }

    @Test
    fun `the canonical tree drops TOML key quoting and default values`() {
        val quoted = topology().let { t ->
            t.copy(providers = t.providers.mapValues { (_, p) -> p.copy(extraHeaders = mapOf("\"x-api-key\"" to "k")) })
        }
        val tree = writer(mapOf(FILE to quoted)).current()
        val headers = tree.getValue("providers").jsonObject.getValue("ex").jsonObject["extra_headers"]
        assertEquals("{\"x-api-key\":\"k\"}", headers.toString())
        assertEquals(setOf("providers", "heads"), tree.keys)
    }
}
