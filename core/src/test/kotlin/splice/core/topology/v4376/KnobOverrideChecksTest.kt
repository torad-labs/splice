// NEW: V4-376 — PUT /api/topology refuses an override the daemon would ignore at boot. Walk of V4-360 on
// 47920066d: the structured editor cleared `trace` under [heads.local.overrides], Write topology landed
// `trace = ""`, and the restarted daemon logged "ignoring heads.local.trace: not a valid bool; the knob
// keeps its default" — the reason every other layer already gives (ConfigService.coerceRejects). The
// writer now asks that same function, so the sentence below is ConfigService's own and no rule is copied.
// The parser is a lookup: the stored text is the stored topology and any composed text is the request.
package splice.core.topology.v4376

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

private const val FILE = "[heads.ex]\nport = 8801\n"
private const val TRACE = "trace"
private const val TIMEOUT = "upstreamTimeoutMs"

class KnobOverrideChecksTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, FILE) } }

    private fun topology(
        overrides: Map<String, String> = emptyMap(),
        defaults: Map<String, String> = emptyMap(),
        other: Map<String, String> = emptyMap(),
    ): Topology = Topology(
        providers = mapOf(
            "ex" to ProviderConfig(
                Dialect.OPENAI_CHAT,
                "https://api.example.com/v1",
                AuthConfig("api-key", env = "EX_KEY"),
                models = listOf(ModelEntry("m1", contextWindow = 128_000L)),
            ),
        ),
        heads = mapOf(
            "ex" to HeadConfig("ex", 8801, "ex/", "m1", overrides = overrides),
            "other" to HeadConfig("ex", 8802, "other/", "m1", overrides = other),
        ),
        defaults = defaults,
    )

    private fun writer(): TopologyWriter = TopologyWriter(
        file,
        tmp.resolve("backups"),
        TopologyParse { text -> if (text == FILE) topology() else requested },
    )

    private var requested: Topology = topology()

    private fun write(topology: Topology): TopologyWriteResult {
        requested = topology
        return writer().write(topology)
    }

    /** What the daemon's own ConfigService says it would ignore for these layers. */
    private fun boot(
        head: Map<String, String> = emptyMap(),
        global: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        ConfigService(
            StatePaths(baseOverride = tmp),
            headOverrides = global,
            perHeadOverrides = mapOf("ex" to head),
            log = LogSink {},
        ).coerceRejects()

    private fun refusal(result: TopologyWriteResult): List<TopologyFinding> {
        assertTrue(result is TopologyWriteResult.Refused, "expected a refusal: $result")
        assertEquals(FILE, Files.readString(file), "a refused write leaves splice.toml byte-identical")
        assertFalse(Files.exists(tmp.resolve("backups")), "a refused write takes no backup")
        return (result as TopologyWriteResult.Refused).findings
    }

    private fun assertRefusedLikeBoot(overrides: Map<String, String>, key: String) {
        val findings = refusal(write(topology(overrides)))
        val why = boot(head = overrides).getValue("heads.ex.$key")
        assertEquals(listOf(TopologyFinding("heads.ex.overrides.$key", why)), findings, key)
    }

    @Test
    fun `an empty trace is refused with boot's own reason`() {
        assertRefusedLikeBoot(mapOf(TRACE to ""), TRACE)
        assertEquals("not a valid bool", boot(head = mapOf(TRACE to "")).getValue("heads.ex.$TRACE"))
    }

    @Test
    fun `a bool word boot does not read is refused`() {
        assertRefusedLikeBoot(mapOf(TRACE to "enabled"), TRACE)
    }

    @Test
    fun `a number that does not parse is refused`() {
        assertRefusedLikeBoot(mapOf(TIMEOUT to "soon"), TIMEOUT)
    }

    @Test
    fun `an unknown knob is refused by name`() {
        assertRefusedLikeBoot(mapOf("tracee" to "false"), "tracee")
    }

    @Test
    fun `the refusal names the head that carries the bad value, and only that one`() {
        val findings = refusal(write(topology(overrides = mapOf(TRACE to "false"), other = mapOf(TRACE to ""))))
        assertEquals(listOf("heads.other.overrides.$TRACE"), findings.map { it.path })
    }

    @Test
    fun `a bad value in the free-form defaults table is refused at defaults`() {
        val findings = refusal(write(topology(defaults = mapOf(TIMEOUT to "soon"))))
        val why = boot(global = mapOf(TIMEOUT to "soon")).getValue(TIMEOUT)
        assertEquals(listOf(TopologyFinding("defaults.$TIMEOUT", why)), findings)
    }

    @Test
    fun `false and a number boot reads still land`() {
        val overrides = mapOf(TRACE to "false", TIMEOUT to "600000")
        assertEquals(emptyMap<String, String>(), boot(head = overrides), "boot reads both")
        val result = write(topology(overrides))
        assertTrue(result is TopologyWriteResult.Written, "expected the write to land: $result")
        assertTrue(Files.readString(file).contains(TRACE), "the write reached splice.toml")
    }
}
