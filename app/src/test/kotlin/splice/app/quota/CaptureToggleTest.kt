// Turning capture off persists a literal trace = "false" override (an absent override means on) and keeps
// the retention and body-cap siblings. Runs in :app because it round-trips through the real topology parser.
package splice.app.quota

import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.wire.CaptureRoutes
import splice.head.wire.TraceSwitch
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

private const val BASE = """
[providers.local]
dialect = "openai-chat"
base_url = "http://127.0.0.1:3105/v1"
auth = { kind = "api-key", env = "LOCAL_API_KEY" }
[[providers.local.models]]
id = "m"
label = "M"
context_window = 200000
[heads.local]
provider = "local"
port = 3102
discovery_prefix = "claude-local--"
pinned_model = "m"
"""

class CaptureToggleTest {
    private data class Fixture(
        val route: CaptureRoutes,
        val file: Path,
        val boot: Topology,
        val switch: TraceSwitch,
    ) {
        fun current(): Topology = TopologyLoader.parse(Files.readString(file))
    }

    private fun fixture(root: Path, extra: String = ""): Fixture {
        val file = root.resolve("splice.toml")
        val initial = BASE.trimIndent() + "\n" + extra
        Files.writeString(file, initial)
        val boot = TopologyLoader.parse(initial)
        val writer = TopologyWriter(file, root.resolve("backups"), TopologyParse(TopologyLoader::parse))
        val switch = TraceSwitch(boot.heads.getValue("local").overrides[Knob.TRACE.key] != "false")
        val head = TurnsHead(
            "local",
            object : HeadCompactSource {
                override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
            },
            trace = switch,
        )
        val lookup = TurnsHeadLookup { key -> if (key == "local") listOf(head) else emptyList() }
        val config = ConfigService(StatePaths(baseOverride = root.resolve("state")))
        return Fixture(CaptureRoutes(lookup, config, TopologyWriterSource { writer }), file, boot, switch)
    }

    @Test
    fun `on then off persists the opt-out instead of inheriting default on`(@TempDir root: Path) {
        val f = fixture(root)
        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":true}""").status)
        assertEquals("true", f.current().heads.getValue("local").overrides[Knob.TRACE.key])

        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":false}""").status)
        assertEquals(mapOf(Knob.TRACE.key to "false"), f.current().heads.getValue("local").overrides)
        assertNotEquals(f.boot.withoutWindows(), f.current().withoutWindows())
        assertTrue(Files.readString(f.file).contains("trace = \"false\""))
    }

    /** The switch is what the head's next request reads, so a write that saved the file moves it at once and a
     *  write that was refused moves nothing. Turning capture off no longer needs a restart to take effect. */
    @Test
    fun `off overrides a boot-time true and the head follows with no restart`(@TempDir root: Path) {
        val f = fixture(root, "[heads.local.overrides]\ntrace = \"true\"\n")
        assertTrue(f.switch.on, "the head booted recording")

        val reply = f.route.write("local", """{"enabled":false}""")

        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals("false", f.current().heads.getValue("local").overrides[Knob.TRACE.key])
        assertFalse(f.switch.on, "the head's next request is no longer recorded")
        assertTrue("\"restart_required\":false" in reply.body, "on or off alone needs no restart: ${reply.body}")
        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":true}""").status)
        assertTrue(f.switch.on, "and on again records the very next request")
        assertTrue("\"enabled\":true" in f.route.read("local").body, "GET reports what the head is doing now")
    }

    @Test
    fun `changing a size still says a restart is needed, and a refused write moves nothing`(@TempDir root: Path) {
        val f = fixture(root, "[heads.local.overrides]\ntrace = \"true\"\n")

        val sized = f.route.write("local", """{"enabled":true,"retention_days":3}""")
        assertTrue("\"restart_required\":true" in sized.body, "a size is read when the store is built: ${sized.body}")

        val refused = f.route.write("local", """{"enabled":false,"retention_days":-5}""")
        if (refused.status != HttpStatusCode.OK) assertTrue(f.switch.on, "a refused write leaves the head recording")
    }

    @Test
    fun `off keeps retention and body limits while setting trace false`(@TempDir root: Path) {
        val siblings = mapOf(Knob.TRACE_RETENTION_DAYS.key to "5", Knob.TRACE_MAX_BODY_CHARS.key to "4096")
        val extra = "[heads.local.overrides]\n" +
            "${Knob.TRACE.key} = \"true\"\n" +
            siblings.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key = \"$value\"" }
        val f = fixture(root, extra)
        assertEquals(HttpStatusCode.OK, f.route.write("local", """{"enabled":false}""").status)
        assertEquals(siblings + (Knob.TRACE.key to "false"), f.current().heads.getValue("local").overrides)
    }

    @Test
    fun `an empty overrides table is the same topology as no table`() {
        val absent = TopologyLoader.parse(BASE.trimIndent() + "\n")
        val empty = TopologyLoader.parse(BASE.trimIndent() + "\n[heads.local.overrides]\n")
        assertEquals(absent.withoutWindows(), empty.withoutWindows())
    }
}
